"""Small composite steps. OAuth and delivery run only through Souz tools."""
import json
from pathlib import Path
import re
import sys
from urllib.parse import parse_qs, urlparse

import state
from mail_content import email_context


def ready(result):
    if result['status'] != 'ready':
        raise ValueError(result)
    return result


def run(operation, config, args):
    def change(op, **data):
        return state.run(config, op, data)

    if operation == 'history-url':
        result = ready(change('profile', response=json.loads(args[0])))
        return result['historyRequest']['url']
    if operation == 'sync':
        query = parse_qs(urlparse(args[0]).query)
        result = change('history', cursor=query['startHistoryId'][0],
                        pageToken=query.get('pageToken', [None])[0], response=json.loads(args[1]))
        backlog = result['status'] == 'backlog_full'
        result = ready(change('status') if backlog else result)
        message = change('claim')
        return {'status': 'ready', 'watchRequired': 'watchRequest' in result,
                'moreHistory': bool(result['pageToken']) or backlog, 'uncertainCount': len(result['uncertain']),
                'pendingRemaining': max(0, result['pendingCount'] - (message['status'] == 'claimed')),
                'message': {'message': message['messageId'], 'claim': message['claim']} if message['status'] == 'claimed' else None}
    if operation == 'watch-body':
        settings = json.loads(Path(config).read_text())
        return json.dumps({'topicName': settings['topicName'], 'labelIds': ['INBOX'], 'labelFilterBehavior': 'include'})

    message_id, claim = args[:2]
    identity = {'messageId': message_id, 'claim': claim}
    info = change('inspect', **identity)
    if operation == 'message-url':
        return info['messageRequest']['url']
    if operation == 'read':
        api = json.loads(args[2])
        try:
            body = {} if api.get('statusCode') == 404 else state.response(api)
            if body is None:
                raise ValueError({'status': 'reconnect_required', 'authorizationUrl': api['authorizationUrl']})
            skip = api.get('statusCode') == 404 or 'INBOX' not in body.get('labelIds', []) or int(body['internalDate']) < info['startedAtMillis']
            if skip:
                change('finish', **identity, outcome='skipped')
                return {'status': 'skipped'}
            headers = {item['name'].lower(): item['value'] for item in body.get('payload', {}).get('headers', [])}
            inputs = {'config': str(config), 'message': message_id, 'claim': claim, **info['destination']}
            if info['notificationMode'] == 'assistant':
                return {'status': 'compose', 'inputs': inputs, 'email': email_context(body)}
            clean = lambda text: re.sub(r'[\x00-\x1f\x7f]+', ' ', str(text)).strip()
            text = 'В Gmail новое письмо'
            if info['notificationMode'] in {'subject', 'summary'}:
                text += '. Тема: ' + (clean(headers.get('subject', ''))[:300] or 'без темы')
            if info['notificationMode'] == 'summary':
                text += '. ' + clean(body.get('snippet', ''))[:500]
            return {'status': 'prepared', 'inputs': {**inputs, 'text': text}}
        except Exception:
            change('finish', **identity, outcome='uncertain')
            raise
    if operation == 'guard-delivery':
        if args[2:4] != [info['destination']['channelType'], info['destination']['channelId']]:
            raise ValueError('Destination differs from connection config')
        return 'ready'
    if operation == 'sent':
        result = json.loads(args[2])
        delivered = result.get('success') is True
        change('finish', **identity, outcome='delivered' if delivered else 'uncertain')
        return {'status': 'delivered' if delivered else 'uncertain'}
    raise ValueError('Unknown flow operation')


if __name__ == '__main__':
    try:
        result = run(sys.argv[1], sys.argv[2], sys.argv[3:])
        sys.stdout.write(result if isinstance(result, str) else json.dumps(result, ensure_ascii=False))
    except Exception as error:
        detail = error.args[0] if error.args and isinstance(error.args[0], dict) else {'error': type(error).__name__}
        print(json.dumps(detail, ensure_ascii=False))
        raise SystemExit(1)
