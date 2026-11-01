"""Allow exactly one OK only on the expected, focused KinoWatch 2.14 play button."""
import argparse
import json
import re
import sys

PACKAGE = 'watch.kino'


class ScreenMismatch(ValueError):
    pass


def unwrap(value):
    for _ in range(6):
        if isinstance(value, str):
            value = json.loads(value)
            continue
        if not isinstance(value, dict):
            break
        if value.get('isError') or value.get('error'):
            raise ScreenMismatch('Устройство вернуло ошибку; OK не отправлен.')
        if isinstance(value.get('elements'), list):
            return value
        if 'structuredContent' in value:
            value = value['structuredContent']
        elif isinstance(value.get('content'), list):
            texts = [c.get('text') for c in value['content'] if c.get('type') == 'text']
            if len(texts) != 1:
                break
            value = texts[0]
        elif 'result' in value:
            value = value['result']
        else:
            break
    raise ScreenMismatch('Текстовая карта экрана не распознана; OK не отправлен.')


def normalized(text):
    return ' '.join(str(text).casefold().replace('ё', 'е').split())


def nodes(elements):
    for node in elements:
        if not isinstance(node, dict):
            raise ScreenMismatch('Некорректная карта экрана; OK не отправлен.')
        yield node
        yield from nodes(node.get('children') or [])


def guard(raw, season='', episode=''):
    if bool(season) != bool(episode) or any(v and not re.fullmatch(r'[1-9][0-9]{0,3}', v) for v in [season, episode]):
        raise ScreenMismatch('Номера сезона и серии должны быть заданы вместе положительными числами.')
    page = unwrap(raw)
    app = page.get('app')
    if isinstance(app, dict):
        app = app.get('packageName') or app.get('package')
    if app != PACKAGE:
        raise ScreenMismatch('Открыто другое приложение; OK не отправлен.')
    elements = list(nodes(page['elements']))
    focused = [n for n in elements if n.get('focused') is True]
    if len(focused) != 1:
        raise ScreenMismatch('Фокус кнопки не подтверждён; OK не отправлен.')
    button = focused[0]
    # get_screen can put the label in a child of the focused clickable node.
    labels = {normalized(n.get('text')) for n in nodes([button]) if n.get('text')}
    if len(labels) != 1:
        raise ScreenMismatch('Подпись кнопки неоднозначна; OK не отправлен.')
    label = labels.pop()
    # Resource ids alone and arbitrary text containing «Смотреть» are insufficient.
    match = re.fullmatch(r'смотреть · s([1-9][0-9]{0,3}) e([1-9][0-9]{0,3})', label)
    movie_button = label == 'смотреть' or bool(re.fullmatch(r'продолжить · \d{1,3}:[0-5]\d(?::[0-5]\d)?', label))
    if button.get('type') != 'button' or not (match or movie_button) or button.get('enabled') is False:
        raise ScreenMismatch('Фокус не на ожидаемой кнопке «Смотреть»; OK не отправлен.')
    if season and (not match or (match[1], match[2]) != (str(int(season)), str(int(episode)))):
        raise ScreenMismatch('На кнопке другая серия или её номер не указан; OK не отправлен.')
    return {'key': 'dpad_center'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('screen')
    parser.add_argument('season')
    parser.add_argument('episode')
    args = parser.parse_args()
    try:
        action = guard(args.screen, args.season, args.episode)
    except (ValueError, TypeError, RecursionError):
        error = sys.exc_info()[1]
        message = str(error) if isinstance(error, ScreenMismatch) else 'Некорректная карта экрана; OK не отправлен.'
        print(json.dumps({'error': message}, ensure_ascii=False), file=sys.stderr)
        return 1
    print(json.dumps(action))
    return 0


if __name__ == '__main__':
    sys.exit(main())
