/* Jimu narrow Linux search launcher. Not a shell or a general command runner. */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t stopping;
static void stop_signal(int sig) { (void)sig; stopping = 1; }
static void fail_at(int line) { dprintf(STDERR_FILENO,"JIMU_NATIVE_FAILURE line=%d errno=%d\n",line,errno); _exit(125); }
#define fail() fail_at(__LINE__)
#define require(ok) do { if (!(ok)) fail(); } while (0)
static void read_exact(int fd, void *out, size_t size) {
    char *p = out;
    while (size) {
        ssize_t n = read(fd, p, size);
        if (n < 0 && errno == EINTR && !stopping) continue;
        require(n > 0); p += n; size -= (size_t)n;
    }
}
static char *field(int fd, size_t max) {
    uint32_t size; read_exact(fd, &size, 4); size = ntohl(size);
    require(size <= max);
    char *p = calloc((size_t)size + 1, 1); require(p != NULL);
    read_exact(fd, p, size); require(strlen(p) == size); return p;
}
static unsigned long long number(const char *p) {
    char *end; errno = 0; unsigned long long n = strtoull(p, &end, 10);
    require(!errno && *p && !*end); return n;
}
/* No realpath/open final-component shortcut: pin every directory from /. */
static int open_path(const char *path, bool directory) {
    require(path[0] == '/' && strlen(path) < 4096);
    char copy[4096]; strcpy(copy, path + 1);
    int current = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC); require(current >= 0);
    char *save = NULL, *part = strtok_r(copy, "/", &save);
    require(part != NULL);
    while (part) {
        require(strcmp(part, ".") && strcmp(part, ".."));
        char *next = strtok_r(NULL, "/", &save);
        int fd = openat(current, part, O_RDONLY | O_NOFOLLOW | O_CLOEXEC |
                        ((next || directory) ? O_DIRECTORY : 0));
        close(current); require(fd >= 0); current = fd; part = next;
    }
    return current;
}
static void inode(int fd, const char *dev, const char *ino, bool directory) {
    struct stat st; require(fstat(fd, &st) == 0);
    require((unsigned long long)st.st_dev == number(dev) &&
            (unsigned long long)st.st_ino == number(ino));
    require(directory ? S_ISDIR(st.st_mode) : S_ISREG(st.st_mode));
    require(!(st.st_mode & (S_ISUID | S_ISGID)));
}
struct identity { pid_t pid; unsigned long long ticks, ns; char boot[80]; };
static struct identity identify(pid_t pid) {
    struct identity id = {.pid = pid}; char name[128], text[4096];
    snprintf(name, sizeof(name), "/proc/%ld/stat", (long)pid);
    int fd = open(name, O_RDONLY | O_CLOEXEC); require(fd >= 0);
    ssize_t n = read(fd, text, sizeof(text)-1); close(fd); require(n > 0); text[n] = 0;
    char *end = strrchr(text, ')'); require(end != NULL); char *save = NULL;
    char *token = strtok_r(end + 2, " ", &save);
    for (int i = 0; i < 19; i++) { require(token != NULL); token = strtok_r(NULL, " ", &save); }
    require(token != NULL); id.ticks = number(token);
    snprintf(name, sizeof(name), "/proc/%ld/ns/pid", (long)pid);
    struct stat st; require(stat(name, &st) == 0); id.ns = (unsigned long long)st.st_ino;
    fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY | O_CLOEXEC); require(fd >= 0);
    n = read(fd, id.boot, sizeof(id.boot)-1); close(fd); require(n > 0);
    id.boot[strcspn(id.boot, "\r\n")] = 0;
    return id;
}
static void print_id(FILE *out, struct identity id) {
    fprintf(out, "{\"pid\":%ld,\"startTicks\":%llu,\"bootId\":\"%s\",\"pidNamespace\":\"%llu\"}",
            (long)id.pid, id.ticks, id.boot, id.ns);
}
static FILE *receipt(int root, const char *name) {
    int fd = openat(root, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    require(fd >= 0); FILE *f = fdopen(fd, "w"); require(f != NULL); return f;
}
static void durable(int root, FILE *out, const char *temporary, const char *name) {
    require(fflush(out) == 0 && fsync(fileno(out)) == 0 && fclose(out) == 0);
    require(renameat(root, temporary, root, name) == 0 && fsync(root) == 0);
}
static void limit(int which, rlim_t soft, rlim_t hard) {
    struct rlimit value = {soft, hard}; require(setrlimit(which, &value) == 0);
}
static long long millis(void) {
    struct timespec ts; require(clock_gettime(CLOCK_MONOTONIC, &ts) == 0);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
static bool logical(const char *path) {
    if (!strcmp(path, ".")) return true;
    if (!*path || *path == '/' || strlen(path) > 2048) return false;
    for (const unsigned char *p = (const unsigned char *)path; *p; p++)
        if (*p < 32 || *p == 127 || *p == '\\' || *p == ':') return false;
    char copy[2049]; strcpy(copy, path); char *save = NULL;
    if (strstr(path, "//") || path[strlen(path)-1] == '/') return false;
    for (char *s = strtok_r(copy,"/", &save); s; s = strtok_r(NULL,"/", &save))
        if (!strcmp(s,".") || !strcmp(s,"..")) return false;
    return true;
}
static void restore_mtimes(int root, int projection) {
    int metadata = openat(root,"mtimes",O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
    if (metadata < 0) { require(errno == ENOENT); return; } /* Empty readiness/manual fixture. */
    uint32_t count; read_exact(metadata,&count,4); count=ntohl(count); require(count <= 4096);
    for (uint32_t i=0;i<count;i++) {
        char *path=field(metadata,2048),*stamp=field(metadata,32); require(logical(path) && strcmp(path,"."));
        char *save=NULL,*part=strtok_r(path,"/",&save); int parent=dup(projection); require(parent>=0);
        for (;;) {
            char *next=strtok_r(NULL,"/",&save);
            int fd=openat(parent,part,O_RDONLY|O_NOFOLLOW|O_CLOEXEC|(next?O_DIRECTORY:0));
            close(parent);require(fd>=0);parent=fd;
            if (!next) break;
            part=next;
        }
        struct stat st;require(fstat(parent,&st)==0 && S_ISREG(st.st_mode) && st.st_nlink==1);
        unsigned long long ns=number(stamp);require(ns>0 && ns<=INT64_MAX);
        struct timespec times[2]={{0,UTIME_OMIT},{(time_t)(ns/1000000000),(long)(ns%1000000000)}};
        require(futimens(parent,times)==0 && fsync(parent)==0);close(parent);free(path);free(stamp);
    }
    char extra;require(read(metadata,&extra,1)==0);close(metadata);
}
int main(int argc, char **argv) {
    /* kill(2) resolves the PID in this process's namespace, independently of proc hidepid.
     * Only ESRCH proves absence. EPERM and all other errors remain unproven. No signal is sent. */
    if (argc == 3 && !strcmp(argv[1], "--probe-absent")) {
        require(getuid() != 0 && geteuid() == getuid());
        unsigned long long value = number(argv[2]); require(value > 0 && value <= INT_MAX);
        if (kill((pid_t)value, 0) == 0) return 1;
        if (errno == ESRCH) return 0;
        fail();
    }
    require(argc == 4 && getuid() != 0 && geteuid() == getuid());
    pid_t parent = getppid(); require(parent > 1);
    require(prctl(PR_SET_PDEATHSIG, SIGKILL) == 0 && getppid() == parent);
    require(prctl(PR_SET_CHILD_SUBREAPER, 1) == 0);
    signal(SIGTERM, stop_signal); signal(SIGINT, stop_signal); signal(SIGPIPE, SIG_IGN);
    int root = open_path(argv[1], true); inode(root, argv[2], argv[3], true);
    int spec = openat(root,"spec",O_RDONLY|O_NOFOLLOW|O_CLOEXEC); require(spec >= 0);
    require(!strcmp(field(spec,32), "JIMU_SEARCH_V1"));
    char *effect = field(spec,36); require(strlen(effect) == 36);
    for (char *p = effect; *p; p++) require((*p >= '0' && *p <= '9') || (*p >= 'a' && *p <= 'f') || *p == '-');
    char *projection = field(spec,4095), *pDev = field(spec,32), *pIno = field(spec,32);
    char *rg = field(spec,4095), *rDev = field(spec,32), *rIno = field(spec,32);
    char *bwrap = field(spec,4095), *bDev = field(spec,32), *bIno = field(spec,32);
    char *tool = field(spec,4), *pattern = field(spec,8192), *path = field(spec,2048), *include = field(spec,4096);
    char extra; require(read(spec,&extra,1) == 0); close(spec);
    require((!strcmp(tool,"glob") || !strcmp(tool,"grep")) && *pattern && logical(path));
    int pfd = open_path(projection,true), rfd = open_path(rg,false), bfd = open_path(bwrap,false);
    inode(pfd,pDev,pIno,true); inode(rfd,rDev,rIno,false); inode(bfd,bDev,bIno,false);
    restore_mtimes(root,pfd);
    struct identity launcher = identify(getpid());
    FILE *out = receipt(root,"launch.part");
    fprintf(out,"{\"effect\":\"%s\",\"launcher\":",effect); print_id(out,launcher); fprintf(out,"}\n");
    durable(root,out,"launch.part","launch.json");
    /* No child can precede the durable receipt. Java has already committed PREPARED. */
    char command; read_exact(STDIN_FILENO,&command,1); require(command == 'S' && !stopping);
    int status[2], gate[2]; require(pipe2(status,O_CLOEXEC) == 0 && pipe2(gate,O_CLOEXEC) == 0);
    limit(RLIMIT_CPU,10,11); limit(RLIMIT_AS,512*1024*1024,512*1024*1024);
    limit(RLIMIT_NOFILE,64,64); limit(RLIMIT_CORE,0,0);
    pid_t child = fork(); require(child >= 0);
    if (child == 0) {
        require(prctl(PR_SET_PDEATHSIG,SIGKILL) == 0 && getppid() == launcher.pid);
        close(status[0]); close(gate[1]);
        require(fcntl(rfd,F_SETFD,0) == 0 && fcntl(pfd,F_SETFD,0) == 0 &&
                fcntl(status[1],F_SETFD,0) == 0 && fcntl(gate[0],F_SETFD,0) == 0);
        int nullfd = open("/dev/null",O_RDONLY|O_CLOEXEC); require(nullfd >= 0);
        require(dup2(nullfd,STDIN_FILENO) >= 0);
        /* Close every inherited non-capability FD, including operation root and sockets. */
        for (int fd = 3; fd < 65536; fd++)
            if (fd != rfd && fd != pfd && fd != bfd && fd != status[1] && fd != gate[0]) close(fd);
        char rd[20],pd[20],sd[20],gd[20],pat[8208],inc[4112];
        snprintf(rd,sizeof(rd),"%d",rfd); snprintf(pd,sizeof(pd),"%d",pfd);
        snprintf(sd,sizeof(sd),"%d",status[1]); snprintf(gd,sizeof(gd),"%d",gate[0]);
        snprintf(pat,sizeof(pat),"%s%s",!strcmp(tool,"glob")?"--glob=":"--regexp=",pattern);
        snprintf(inc,sizeof(inc),"--glob=%s",include);
        char *args[64] = {bwrap,"--unshare-all","--die-with-parent","--new-session","--cap-drop","ALL",
            "--clearenv","--setenv","LC_ALL","C.UTF-8","--ro-bind-fd",rd,"/rg","--ro-bind-fd",pd,
            "/workspace","--proc","/proc","--chdir","/workspace","--info-fd",sd,"--block-fd",gd,
            "--","/rg","--no-config",NULL};
        int i = 27;
        if (!strcmp(tool,"glob")) {
            args[i++]="--files"; args[i++]=pat; args[i++]="--sort=modified";
            args[i++]="--no-ignore"; args[i++]="--hidden";
            char *excludes[] = {"--glob=!**/.git","--glob=!**/.git/**","--glob=!**/.svn","--glob=!**/.svn/**",
                "--glob=!**/.hg","--glob=!**/.hg/**","--glob=!**/.bzr","--glob=!**/.bzr/**",
                "--glob=!**/.jj","--glob=!**/.jj/**","--glob=!**/.sl","--glob=!**/.sl/**"};
            for (size_t j=0;j<sizeof(excludes)/sizeof(excludes[0]);j++) args[i++]=excludes[j];
        } else { args[i++]="--json"; args[i++]=pat; if (*include) args[i++]=inc; }
        args[i++]="--"; args[i++]=path; args[i]=NULL;
        char *env[] = {"LC_ALL=C.UTF-8",NULL}; fexecve(bfd,args,env); fail();
    }
    close(status[1]); close(gate[0]); close(pfd); close(rfd); close(bfd);
    struct identity bubble = identify(child), init = {0};
    int bubblefd = (int)syscall(SYS_pidfd_open,child,0); require(bubblefd >= 0);
    int initfd = -1, code = 125; char info[4097] = {0}; size_t used = 0;
    long long until = millis()+5000;
    while (!stopping && millis() < until && used < 4096) {
        struct pollfd pollfd = {status[0],POLLIN,0};
        if (poll(&pollfd,1,50) <= 0) continue;
        ssize_t n = read(status[0],info+used,4096-used); if (n <= 0) break;
        used += (size_t)n; info[used]=0;
        char *key = strstr(info,"\"child-pid\"");
        if (key && strchr(key,':') && strchr(key,'}')) {
            char *value = strchr(key,':')+1; pid_t pid = (pid_t)strtol(value,NULL,10); require(pid > 1);
            initfd = (int)syscall(SYS_pidfd_open,pid,0); require(initfd >= 0); init = identify(pid);
            out = receipt(root,"started.part"); fprintf(out,"{\"effect\":\"%s\",\"identity\":{\"launcher\":",effect);
            print_id(out,launcher); fprintf(out,",\"bubblewrap\":"); print_id(out,bubble);
            fprintf(out,",\"init\":"); print_id(out,init); fprintf(out,"}}\n");
            durable(root,out,"started.part","started.json");
            struct pollfd input = {STDIN_FILENO,POLLIN|POLLHUP,0};
            while (!stopping && millis() < until) {
                if (poll(&input,1,50) <= 0) continue;
                if (read(STDIN_FILENO,&command,1) != 1 || command != 'G') stopping=1;
                break;
            }
            if (!stopping && command == 'G') { require(write(gate[1],"G",1) == 1); close(gate[1]); gate[1]=-1; }
            else stopping=1;
            break;
        }
    }
    close(status[0]);
    if (initfd < 0) stopping=1;
    long long stopAt = 0; bool killed = false;
    for (;;) {
        if (stopping && !stopAt) {
            syscall(SYS_pidfd_send_signal,bubblefd,SIGTERM,NULL,0);
            if (initfd >= 0) syscall(SYS_pidfd_send_signal,initfd,SIGTERM,NULL,0);
            stopAt=millis();
        }
        if (stopAt && !killed && millis()-stopAt >= 3000) {
            syscall(SYS_pidfd_send_signal,bubblefd,SIGKILL,NULL,0);
            if (initfd >= 0) syscall(SYS_pidfd_send_signal,initfd,SIGKILL,NULL,0);
            killed=true;
        }
        int statusCode; pid_t got=waitpid(-1,&statusCode,WNOHANG);
        if (got == child && !stopping) code=WIFEXITED(statusCode)?WEXITSTATUS(statusCode):125;
        if (got < 0 && errno == ECHILD) break;
        if (got < 0 && errno != EINTR) fail();
        struct timespec pause = {0,10000000}; nanosleep(&pause,NULL);
    }
    if (initfd >= 0) close(initfd);
    close(bubblefd); if (gate[1] >= 0) close(gate[1]); close(root);
    return stopping ? 125 : code;
}
