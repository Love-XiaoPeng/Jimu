"""Real TCP deadline faults on an original PENDING slot; every peer is joined in the foreground."""
import argparse
import json
from pathlib import Path
import shutil
import socket
import subprocess
import threading
import time
import traceback

from session_event_verify import SessionExperiment


def packet(peer):
    def exact(count):
        result = b''
        while len(result) < count:
            part = peer.recv(count-len(result))
            if not part: raise EOFError()
            result += part
        return result
    header = exact(4)
    return header + exact(int.from_bytes(header[:3], 'little'))


class Peer:
    def __init__(self, upstream, mode):
        self.upstream, self.mode = upstream, mode
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen(1)
        self.listener.settimeout(8)
        self.port = self.listener.getsockname()[1]
        self.closed = threading.Event()
        self.hold = threading.Event()
        self.errors, self.sockets, self.threads = [], [], []
        self.auth_seen = False
        self.query = None
        self.thread = threading.Thread(target=self.run)
        self.thread.start()

    def run(self):
        try:
            client, _ = self.listener.accept()
            self.sockets.append(client)
            client.settimeout(5)
            if self.mode == 'no-greeting':
                assert client.recv(1) == b''
                self.closed.set()
                return
            server = socket.create_connection(('127.0.0.1', self.upstream), 3)
            self.sockets.append(server)
            server.settimeout(5)
            greeting = packet(server)
            time.sleep(.8)
            client.sendall(greeting)
            login = packet(client)
            self.auth_seen = True
            if self.mode == 'authentication':
                assert client.recv(1) == b''
                self.closed.set()
                return
            server.sendall(login)

            def requests():
                try:
                    while True:
                        raw = packet(client)
                        payload = raw[4:]
                        if payload[:1] == b'\x03':
                            sql = payload[1:].decode('utf-8', errors='replace').lower()
                            target = ('select @@session.innodb_lock_wait_timeout' if self.mode == 'sql'
                                else 'update task set revision=revision+1')
                            if target in sql:
                                self.query = sql
                                self.hold.set()
                        server.sendall(raw)
                except EOFError:
                    self.closed.set()
                except OSError as error:
                    if not self.closed.is_set(): self.errors.append(repr(error))

            forward = threading.Thread(target=requests)
            self.threads.append(forward)
            forward.start()
            try:
                while not self.closed.is_set():
                    response = packet(server)
                    if self.hold.is_set():
                        assert self.closed.wait(4), 'client did not close stalled transaction'
                        break
                    client.sendall(response)
            except (EOFError, OSError) as error:
                if not self.closed.is_set(): self.errors.append(repr(error))
        except BaseException:
            self.errors.append(traceback.format_exc())
        finally:
            for peer in self.sockets:
                try: peer.shutdown(socket.SHUT_RDWR)
                except OSError: pass
                peer.close()
            self.listener.close()
            for child in self.threads: child.join(6)

    def finish(self):
        self.thread.join(8)
        assert not self.thread.is_alive() and all(not t.is_alive() for t in self.threads), 'TCP peers collected'
        assert self.closed.is_set() and not self.errors, self.errors
        if self.mode != 'no-greeting': assert self.auth_seen
        if self.mode in ('sql', 'write'): assert self.query is not None
        return {'mode':self.mode, 'clientClosed':True, 'allPeersJoined':True,
            'authenticationSeen':self.auth_seen, 'heldSql':self.query, 'errors':self.errors}


class ConnectionExperiment(SessionExperiment):
    def execute(self):
        task = self.task('R1 connection deadline original slot', [{'seq':0}])
        worker = self.start('r1-pending-worker', 'worker', 'SESSION_COMMIT_BEFORE')
        self.barrier(worker)
        classes = self.root/'session-test-classes'
        shutil.copytree(Path(__file__).resolve().parents[1]/'target/test-classes', classes)
        baseline = self.snapshot(task)
        receipts = []
        for mode in ('no-greeting', 'authentication', 'sql', 'write'):
            peer = Peer(self.dbport, mode)
            url = self.common['jimu.db.url'].replace(':'+str(self.dbport)+'/', ':'+str(peer.port)+'/')
            url += '&sslMode=DISABLED&allowPublicKeyRetrieval=true'
            try:
                command = [self.java, '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.SessionLedgerScenario',
                    '-Dloader.path='+str(classes), '-cp', str(self.jar),
                    'org.springframework.boot.loader.launch.PropertiesLauncher',
                    str(worker['folder']/'runtime.properties'), task, 'connection-timeout', url]
                result = subprocess.run(command, text=True, capture_output=True, timeout=20)
                (self.evidence/(mode+'.log')).write_text(result.stdout+result.stderr)
                self.assert_that(result.returncode == 0 and 'PASS connection deadline' in result.stdout,
                    mode+' bounded original slot exits', exit=result.returncode, output=result.stdout[-3000:], stderr=result.stderr[-2000:])
            finally:
                receipts.append(peer.finish())
            self.assert_that(self.snapshot(task) == baseline, mode+' no tool/binding/receipt or task change')
            time.sleep(.25)
            self.assert_that(self.snapshot(task) == baseline, mode+' no late writer after return')
        (self.evidence/'tcp-cleanup.json').write_text(json.dumps(receipts, indent=2))
        (worker['folder']/'release').touch()
        self.state(task, 'SUCCEEDED')
        self.stop(worker)
        self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '1',
            'normal same slot completes once after transport faults')

    def snapshot(self, task):
        return {table:self.sql('SELECT '+fields+' FROM '+table+" WHERE "+key+"='"+task+"'"+order+';')
            for table,fields,key,order in [
                ('task','revision,tools_used,next_event_seq','id',''),
                ('invocation','id,state,output_digest','task_id',' ORDER BY id'),
                ('invocation_session_read','invocation_id,outcome,receipt_seq,output_digest','task_id',' ORDER BY invocation_id'),
                ('session_public_event','seq,event_digest','task_id',' ORDER BY seq'),
                ('reservation',"id,category,state,actual",'task_id',' ORDER BY id')]}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    experiment = ConnectionExperiment(args.jar, args.evidence)
    try:
        experiment.prepare()
        experiment.execute()
    except BaseException:
        (experiment.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally: experiment.cleanup()
