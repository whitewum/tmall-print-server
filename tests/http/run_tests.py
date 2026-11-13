#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Real HTTP tests with JVM Android stubs and a fake renderer/sender; never prints."""
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.parse import quote

ROOT = Path(__file__).resolve().parent
PROJECT = next(p for p in ROOT.parents if (p / 'AndroidManifest.xml').is_file())
JDK = Path(os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip()) / 'bin'
HOST = os.environ.get('TMALL_HTTP_TEST_IP')
if not HOST:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route_socket:
        route_socket.connect(('192.0.2.1', 9))
        HOST = route_socket.getsockname()[0]
PORT = 8080
results = []


def exchange(method='GET', target='/', body=b'', headers=None, duplicate=None):
    values = {'Host': f'{HOST}:{PORT}', 'Connection': 'close'}
    if headers:
        values.update(headers)
    lines = [f'{method} {target} HTTP/1.1'] + [f'{k}: {v}' for k, v in values.items()]
    if duplicate:
        lines += duplicate
    request = ('\r\n'.join(lines) + '\r\n\r\n').encode('ascii') + body
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as connection:
        connection.settimeout(8)
        connection.bind((HOST, 0))
        connection.connect((HOST, PORT))
        connection.sendall(request)
        output = bytearray()
        while True:
            try:
                part = connection.recv(65536)
            except ConnectionResetError:
                if output:
                    break
                raise
            if not part:
                break
            output.extend(part)
    head, content = bytes(output).split(b'\r\n\r\n', 1)
    status = int(head.split(b' ', 2)[1])
    response_headers = {}
    for line in head.decode('ascii').split('\r\n')[1:]:
        name, value = line.split(':', 1)
        response_headers[name.lower()] = value.strip()
    assert len(content) == int(response_headers['content-length']), (head, len(content))
    return status, response_headers, content


def check(name, actual, expected):
    passed = actual == expected
    results.append({'test': name, 'actual': actual, 'expected': expected, 'pass': passed})
    print(('PASS' if passed else 'FAIL') + f' {name}: {actual} (expected {expected})', flush=True)
    assert passed, name


def job_state(job_id, target_state):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        status, _, body = exchange(target='/api/jobs')
        assert status == 200
        jobs = json.loads(body)['jobs']
        for job in jobs:
            if job['id'] == job_id and job['state'] == target_state:
                return job
        time.sleep(0.05)
    raise AssertionError((job_id, target_state, jobs))


def main():
    classes = ROOT / 'classes'
    classes.mkdir(exist_ok=True)
    sources = [ROOT / 'stubs/android/content/Context.java', ROOT / 'stubs/android/util/Log.java',
               PROJECT / 'src/net/local/p1007bridge/WebPrintServer.java', PROJECT / 'src/net/local/p1007bridge/WebPage.java',
               ROOT / 'harness/net/local/p1007bridge/HttpHarness.java']
    subprocess.run([str(JDK / 'javac'), '-source', '8', '-target', '8', '-encoding', 'UTF-8',
                    '-d', str(classes)] + list(map(str, sources)), check=True)
    cache = Path(tempfile.mkdtemp(prefix='fake-cache-', dir=ROOT))
    java_log = open(ROOT / 'java.log', 'w')
    process = subprocess.Popen([str(JDK / 'java'), '-cp', str(classes),
                                'net.local.p1007bridge.HttpHarness', str(cache)],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=java_log, text=True)
    final_output = ''
    try:
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        assert selector.select(8), 'Harness did not become ready; check java.log'
        ready = process.stdout.readline().strip()
        assert ready == 'READY', ready
        selector.close()

        status, response_headers, page = exchange()
        check('home page', status, 200)
        check('HTML Chinese title', 'P1007 打印' in page.decode('utf-8'), True)
        check('PDF pages input present', '页码（PDF）' in page.decode('utf-8'), True)
        check('no CORS allow-origin', 'access-control-allow-origin' in response_headers, False)
        match = re.search(rb"const token='([0-9a-f]+)'", page)
        assert match, 'CSRF token missing from HTML'
        token = match.group(1).decode('ascii')
        payload = b'%PDF-FAKE-HTTP-TEST-NO-PRINT\n'
        good = {'Content-Length': str(len(payload)), 'Content-Type': 'application/pdf',
                'X-Print-Token': token, 'X-File-Name': 'http-test.pdf',
                'Origin': f'http://{HOST}:{PORT}'}
        status, _, body = exchange('POST', '/api/jobs?copies=1', payload, good)
        check('valid upload', status, 202)
        job_id = json.loads(body)['id']
        completed = job_state(job_id, 'sent')
        check('fake task eventually sent', completed['state'], 'sent')
        check('filename preserved', completed['name'], 'http-test.pdf')
        check('default pages is all', completed['pages'], '')
        check('completed cache cleaned', sorted(p.name for p in cache.iterdir()), [])

        def reject(name, expected, target='/api/jobs?copies=1', modified=None, data=payload, duplicates=None):
            h = dict(good)
            if modified:
                for k, v in modified.items():
                    if v is None:
                        h.pop(k, None)
                    else:
                        h[k] = v
            status, _, body = exchange('POST', target, data, h, duplicates)
            check(name, status, expected)
            if expected >= 400:
                check(name + ' JSON error', bool(json.loads(body).get('error')), True)

        reject('invalid copies', 400, target='/api/jobs?copies=0')
        reject('wrong token', 403, modified={'X-Print-Token': 'wrong'})
        reject('wrong Origin', 403, modified={'Origin': 'http://evil.example'})
        reject('wrong Host', 403, modified={'Host': 'evil.example:8080', 'Origin': None})
        reject('oversize upload', 413, modified={'Content-Length': str(20 * 1024 * 1024 + 1)}, data=b'')
        reject('duplicate Content-Length', 400, duplicates=['Content-Length: ' + str(len(payload))])
        reject('empty file', 400, modified={'Content-Length': '0'}, data=b'')
        reject('unsupported file type', 415, modified={'Content-Type': 'text/plain'})
        reject('missing Content-Length', 411, modified={'Content-Length': None})
        reject('chunked upload forbidden', 400, modified={'Transfer-Encoding': 'chunked'})
        reject('copies above ten', 400, target='/api/jobs?copies=11')
        reject('duplicate pages', 400, target='/api/jobs?copies=1&pages=1&pages=2')
        reject('duplicate copies', 400, target='/api/jobs?copies=1&copies=2')
        reject('unknown query parameter', 400, target='/api/jobs?copies=1&unknown=1')
        reject('invalid pages characters', 400, target='/api/jobs?copies=1&pages=all')
        reject('oversize pages text', 400, target='/api/jobs?copies=1&pages=' + ('1' * 201))
        reject('invalid pages URL encoding', 400, target='/api/jobs?copies=1&pages=%ZZ')
        reject('cross-site Fetch metadata', 403, modified={'Sec-Fetch-Site': 'cross-site'})
        check('rejected requests leave cache empty', sorted(p.name for p in cache.iterdir()), [])

        for name, mime in [('http-test.jpg', 'image/jpeg'), ('http-test.png', 'image/png')]:
            h = dict(good, **{'Content-Type': mime, 'X-File-Name': name})
            status, _, body = exchange('POST', '/api/jobs?copies=2', payload, h)
            check(mime + ' upload', status, 202)
            task = job_state(json.loads(body)['id'], 'sent')
            check(mime + ' copies', task['copies'], 2)
        for selection in ['1,3-5', '1， 3-5']:
            encoded = quote(selection, safe='')
            status, _, body = exchange('POST', '/api/jobs?pages=' + encoded + '&copies=1', payload, good)
            check('selected PDF pages upload ' + selection, status, 202)
            task = job_state(json.loads(body)['id'], 'sent')
            check('selected PDF pages in job ' + selection, task['pages'], selection)
            check('selected PDF pages reached renderer ' + selection,
                  ('RENDER_PAGES=' + selection) in (ROOT / 'java.log').read_text(), True)
        check('all successful inputs and outputs deleted', sorted(p.name for p in cache.iterdir()), [])
        status, _, body = exchange(target='/api/status')
        check('status endpoint', status, 200)
        check('all capacity released', json.loads(body)['pending'], 0)
        status, _, body = exchange(target='/api/jobs')
        check('only accepted tasks recorded', len(json.loads(body)['jobs']), 5)
    finally:
        try:
            final_output, _ = process.communicate('\n', timeout=8)
        except subprocess.TimeoutExpired:
            process.kill()
            final_output, _ = process.communicate()
        java_log.close()
        if process.returncode != 0:
            print((ROOT / 'java.log').read_text())
        check('harness shutdown', process.returncode, 0)
        check('shutdown cache cleaned', sorted(p.name for p in cache.iterdir()), [])
        check('only fake sender used', final_output.strip(), 'COUNTS 5 5')
        report = {'host': HOST, 'port': PORT, 'real_printer_used': False,
                  'source_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sources[2:4]},
                  'checks': results, 'passed': sum(x['pass'] for x in results), 'total': len(results),
                  'harness_output': final_output.strip(), 'cache_directory': str(cache)}
        (ROOT / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(f"{report['passed']}/{report['total']} checks passed; {ROOT / 'results.json'}")
        if cache.exists() and not any(cache.iterdir()):
            cache.rmdir()


if __name__ == '__main__':
    main()
