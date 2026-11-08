import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from urllib import error, request

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import probe_portal


class ProbeTests(unittest.TestCase):
    def test_push_protocol_and_delivery_state(self):
        subprocess.run(['node', '--test', str(HERE / 'tests/push.test.mjs')], check=True)

    def test_portal_and_third_party_links_are_distinguished(self):
        parser = probe_portal.NoticeList(probe_portal.NOTICES)
        parser.feed('<a href="https://other.example/xntz_content.jsp?wbnewsid=99">x</a>'
                    '<a href="/xntz_content.jsp?wbnewsid=42&wbtreeid=1154">y</a>')
        self.assertEqual(len(parser.links), 1)
        self.assertFalse(parser.has_password)
        parser.feed('<input type="password">')
        self.assertTrue(parser.has_password)


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / 'feed.json'
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0))
            port = s.getsockname()[1]
        self.url = 'http://127.0.0.1:' + str(port)
        env = dict(os.environ, PORT=str(port), BIND_HOST='127.0.0.1',
                   PUBLIC_ORIGIN=self.url, STATE_DIR=str(self.root), PUBLIC_FEED=str(self.data))
        backend = subprocess.Popen(['node', str(HERE / 'server/server.mjs')], env=env,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(self.close_process, backend)
        for _ in range(80):
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=.2):
                    break
            except OSError:
                time.sleep(.05)
        else:
            self.fail('API did not start')

    @staticmethod
    def close_process(process):
        process.terminate()
        process.wait(timeout=5)

    def fetch(self, path, body=None, origin=None):
        data = json.dumps(body).encode() if body is not None else None
        headers = {'Content-Type': 'application/json'}
        if origin:
            headers['Origin'] = origin
        req = request.Request(self.url + path, data=data, headers=headers)
        try:
            response = request.urlopen(req, timeout=3)
        except error.HTTPError as err:
            response = err
        with response:
            return response.status, json.load(response)

    def test_public_feed_requires_allowance_and_unique_ids(self):
        self.assertEqual(self.fetch('/api/notices'), (503, {'error': 'public_feed_not_configured'}))
        item = dict(id='A1', title='Example', publishedAt='2026-11-07', body='synthetic')
        def feed(visibility, notices):
            self.data.write_text(json.dumps(dict(visibility=visibility, notices=notices)), encoding='utf8')
            return self.fetch('/api/notices')
        self.assertEqual(feed('private', [item])[0], 503)
        self.assertEqual(feed('public', [item, item])[0], 503)
        self.assertEqual(feed('public', [{**item, 'url': 'https://my.bupt.edu.cn/?ticket=synthetic'}])[0], 503)
        status, body = feed('public', [{**item, 'hidden': 'never deliver'}, {**item, 'id':'A2','publishedAt':'2026-11-08'}])
        self.assertEqual(status, 200)
        self.assertEqual([n['id'] for n in body['notices']], ['A2','A1'])
        self.assertNotIn('hidden', body['notices'][1])

    def test_feedback_receipt_is_private_and_requires_same_origin(self):
        sample = dict(message='Synthetic feedback', version='0.9.1', commit='abcdef0')
        self.assertEqual(self.fetch('/api/feedback', sample)[0], 403)
        self.assertEqual(self.fetch('/api/feedback', sample, 'https://other.example')[0], 403)
        self.assertFalse((self.root / 'feedback.ndjson').exists())
        self.assertEqual(self.fetch('/api/feedback', {**sample,'token':'fixture'}, self.url)[0], 400)
        self.assertEqual(self.fetch('/api/feedback', sample, self.url), (201, {'ok':True}))
        self.assertEqual(self.fetch('/api/feedback')[0], 404)
        record = json.loads((self.root/'feedback.ndjson').read_text(encoding='utf8'))
        self.assertEqual(record['message'], sample['message'])
        self.assertEqual(set(record), {'date','message','version','commit'})

    def test_anonymous_subscription_api_has_no_broadcast_or_listing(self):
        status, config = self.fetch('/api/push/config')
        self.assertEqual(status,200)
        self.assertTrue(config['enabled'])
        self.assertRegex(config['publicKey'], r'^[\w-]{87}$')
        sub = {'endpoint':'https://web.push.apple.com/synthetic_local_fixture','token':'d'*64}
        self.assertEqual(self.fetch('/api/push/subscribe',sub)[0],403)
        self.assertEqual(self.fetch('/api/push/subscribe',sub,self.url)[0],201)
        self.assertEqual(self.fetch('/api/push/subscribe',sub,self.url)[0],200)
        self.assertEqual(self.fetch('/api/push/subscribe')[0],404)
        self.assertEqual(self.fetch('/api/push/broadcast',sub,self.url)[0],404)
        self.assertEqual(self.fetch('/api/push/test',{**sub,'token':'e'*64},self.url)[0],403)
        self.assertEqual(self.fetch('/api/push/unsubscribe',sub,self.url)[0],200)
        self.assertEqual(self.fetch('/api/push/test',sub,self.url)[0],410)


if __name__ == '__main__':
    unittest.main()
