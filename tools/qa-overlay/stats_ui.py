"""Read signed Release 1's native message and Statistics accessibility labels on the fresh AVD."""
import json
import re
from flow import PKG, P, chat, confirm, first, label, nodes, shell, summary, tap, time, uiwait

evidence = {}
def observe(key):
    labels = summary(nodes())
    evidence[key] = labels
    print(key, json.dumps(labels, ensure_ascii=False), flush=True)
    (P / 'stats-ui.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
    return labels

def transfer():
    from shlex import quote
    proof = json.loads((P / 'stage/build-provenance.json').read_text())
    name = f"rikkahub-{proof['version_name']}-{proof['source_commit'][:12]}-arm64.apk"
    url = f"https://github.com/0425-sketch/rikkahub/releases/download/{proof['tag']}/{name}"
    query = f"SELECT count(*) FROM downloads WHERE status=200 AND uri='{url}'"
    roots = ['/data/data', '/data/user_de/0']
    for root in roots:
        db = root + '/com.android.providers.downloads/databases/downloads.db'
        if shell('test -f '+db+' && echo present || true') != 'present':
            continue
        if int(shell('sqlite3 '+db+' '+quote(query))) > 0:
            evidence['android_download_manager_origin'] = url
            print('Successful Android Download Manager transfer from public Release:', url, flush=True)
            return
    raise AssertionError('Cannot associate completed transfer with Download Manager URI ' + url)

def messages():
    state = json.loads((P / 'chat.json').read_text())
    result = shell('am start -W -f 0x24000000 -n ' + PKG + '/me.rerere.rikkahub.RouteActivity --es conversationId ' + state['id'])
    print(result, flush=True)

def usage():
    messages()
    for _ in range(6):
        labels = observe('message')
        if '140 ctx' in labels and '(30 cached)' in labels:
            break
        # Conversation with multiple messages. Scroll to the newest reply.
        shell('input swipe 370 1180 370 460 360')
        time.sleep(.5)
    assert '140 ctx' in labels and '(30 cached)' in labels, labels
    assert any(re.search(r'\d+(?:\.\d+)? tok/s', item) for item in labels), labels
    row = [n for n in nodes() if label(n) == '140 ctx'][-1]
    tap(row)
    uiwait(lambda ns: first(ns, lambda s: s == 'Cached 30'), 12)
    details = observe('message_usage_details')
    assert all(s in details for s in ('Cached 30', 'Input 90', 'Output 20')), details
    assert any('140' in s for s in details), details

def stats():
    # Use a fresh normal launch to dismiss a tooltip or diagnostic screen.
    shell('am force-stop '+PKG)
    messages()
    tap(uiwait(lambda ns:first(ns,lambda s:s=='Messages'),25))
    tap(uiwait(lambda ns:first(ns,lambda s:'统计数据' in s),20))
    uiwait(lambda ns:first(ns,lambda s:s=='Statistics'),18)
    seen = set()
    for ix in range(9):
        labels = observe('statistics_scroll_'+str(ix))
        seen.update(labels)
        if any('of 4 requests' in s for s in seen) and '25.0%' in seen:
            break
        shell('input swipe 520 1080 520 750 440')
        time.sleep(.25)
    # Current 30-day window contains three complete 120/20/30 replies and one prompt-only 77 reply.
    needed = {'Input Tokens', '437', 'Output Tokens', 'At least 60 (partly unreported)',
              'Cached Tokens', 'At least 90 (partly unreported)', 'Cache hit rate', '25.0%'}
    assert needed <= seen, (needed-seen,seen)
    assert any('3 of 4 requests' in s for s in seen),seen

if __name__ == '__main__':
    confirm()
    assert 'versionCode=210 ' in shell('dumpsys package '+PKG)
    chat(False)
    errors = {}
    for name, func in [('android_download_manager', transfer), ('message', usage), ('statistics', stats)]:
        try:
            func()
        except Exception as error:
            errors[name] = repr(error)
            print(name,repr(error),flush=True)
    assert not errors, errors
