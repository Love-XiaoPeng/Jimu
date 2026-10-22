"""Run the exact pre-P4 workspace implementation against new format2/3 state, offline."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

BASE = 'f6a97168281990fd036cc18bf0ddef8045d90a6d'
STORE = 'src/main/java/io/github/lovexiaopeng/jimu/adapters/storage/SecureWorkspaceStore.java'


def verify_legacy(jar, java, evidence):
    evidence.mkdir()
    root = Path(tempfile.mkdtemp(prefix='jimu-p4-legacy-'))
    receipt = {'base': BASE, 'uid': 65534}
    try:
        repo = Path(__file__).resolve().parents[1]
        source = subprocess.run(['git', 'show', BASE + ':' + STORE], cwd=repo,
                                capture_output=True, check=True).stdout
        receipt['legacySourceSha256'] = hashlib.sha256(source).hexdigest()
        old = root / 'old'; old.mkdir()
        current = root / 'current'; current.mkdir()
        workspace = root / 'workspace'; workspace.mkdir(mode=0o700)
        dependencies = root / 'lib'; dependencies.mkdir()
        classes = root / 'classes'; classes.mkdir()
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/jackson-'):
                    (dependencies / Path(name).name).write_bytes(archive.read(name))
                elif name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
                    target=classes/name.removeprefix('BOOT-INF/classes/')
                    target.parent.mkdir(parents=True,exist_ok=True)
                    target.write_bytes(archive.read(name))
        classpath = str(classes.resolve()) + ':' + str(dependencies / '*')
        old_source = root / 'SecureWorkspaceStore.java'; old_source.write_bytes(source)
        seed = root / 'Seed.java'
        seed.write_text('''
import java.nio.file.*;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
public class Seed {
 public static void main(String[] a) throws Exception {
  var s = new SecureWorkspaceStore(Path.of(a[0]));
  s.initialize("legacy",a[1],Boolean.parseBoolean(a[2]),()->{});
  var p=s.bind("legacy",a[1],java.util.UUID.randomUUID().toString(),()->{});
  p.read("a",1,2000);
  s.bind("legacy",a[1],java.util.UUID.randomUUID().toString(),()->{}).write("a","legacy text");
 }
}
''')
        probe = root / 'Probe.java'
        probe.write_text('''
import java.nio.file.*;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
public class Probe {
 public static void main(String[] a) throws Exception {
  try {
   var s=new SecureWorkspaceStore(Path.of(a[0]));
   var result=s.bind("legacy",a[1],java.util.UUID.randomUUID().toString(),()->{}).read("a",1,2000);
   if(a[2].equals("3") || !result.content().equals("1: legacy text\\n")) throw new AssertionError();
   System.out.println("PASS exact old worker reads format2");
  } catch(FileFailure failure) {
   if(!a[2].equals("3")) throw failure;
   System.out.println("PASS exact old worker refuses format3: "+failure.code());
  }
 }
}
''')
        javac = str(Path(java).parent / 'javac')
        subprocess.run([javac,'-cp',classpath,'-d',str(current),str(seed)],check=True)
        subprocess.run([javac,'-cp',classpath,'-d',str(old),str(old_source),str(probe)],check=True)
        for path in [root,*root.rglob('*')]: os.chown(path,65534,65534)
        prefix = ['setpriv','--reuid=65534','--regid=65534','--clear-groups',java]
        outputs=[]
        for version in [2,3]:
            task='10000000-0000-0000-0000-00000000000'+str(version)
            subprocess.run(prefix+['-cp',str(current)+':'+classpath,'Seed',str(workspace),task,str(version==3).lower()],check=True,timeout=30)
            state=workspace/(hashlib.sha256(('legacy:'+task).encode()).hexdigest()+'.json')
            before=state.read_bytes()
            result=subprocess.run(prefix+['-cp',str(old)+':'+classpath,'Probe',str(workspace),task,str(version)],capture_output=True,text=True,timeout=30)
            (evidence/('format'+str(version)+'.log')).write_text(result.stdout+result.stderr)
            assert result.returncode==0,result.stdout+result.stderr
            outputs.append(result.stdout)
            if version==3: assert state.read_bytes()==before,'old worker must leave format3 bytes unchanged'
        (evidence/'scenario.log').write_text(''.join(outputs))
        print(''.join(outputs),flush=True)
        receipt['exit']=0
    finally:
        shutil.rmtree(root)
        receipt['rootRemoved']=not root.exists()
        (evidence/'receipt.json').write_text(json.dumps(receipt,indent=2))
