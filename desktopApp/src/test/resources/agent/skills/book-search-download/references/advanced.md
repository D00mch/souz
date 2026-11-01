# Текст и необязательная S3-ссылка

## Извлечь текст

`scripts/prepare_text.py`:
`--input ABSOLUTE_FILE --format fb2|txt --title TITLE --author AUTHOR --source-url BOOK_URL --rights public-domain|permission --limit CHARACTERS --output-dir EMPTY_ABSOLUTE_DIRECTORY`.

Создаёт book.txt, part-*.txt и manifest.json. Сохраняет абзацы, заголовки внутри p,
стихотворные строки и примечания; изображения и метаданные FB2 в текст не переносятся.
Исходный FB2 остаётся главным файлом для вложения. Части при соединении дают весь
извлечённый текст. limit считает Unicode-символы Python, не байты и не UTF-16:
не используй его как гарантию лимита Telegram/VK. Массовая отправка частей не нужна
для обычного запроса книги.

## S3 по явному запросу ссылки или отдельного сохранения

`scripts/s3_delivery.py` сохранён для совместимости. Он использует частный конфиг
`/souz/state/librusec-books/s3.json`; это отдельное хранилище, не штатный fileId Souz.
Не читай конфиг в вывод инструмента. Сохраняй приватный manifest вне bundle,
каталог 700, файл 600. Signed URL — секрет доступа; выдавай его только нужному получателю.

- `upload --file ABSOLUTE_FILE --manifest NEW_PRIVATE_MANIFEST --content-type application/x-fictionbook+xml --expiry 3600`
- `head --manifest PRIVATE_MANIFEST`
- `delete --manifest PRIVATE_MANIFEST` — только когда удаление разрешено.

Конфиг поддерживает существующий root HTTPS endpoint, path style и SigV4. Не меняй
bucket/policy/ACL и не устанавливай зависимости. Upload возвращает только путь manifest;
успешный manifest содержит presigned_url и SHA-256. Истечение ссылки не удаляет объект.
Upload не является отправкой в канал и не создаёт fileId для SendMessageToChannel.
После неопределённого PUT проверь приватный manifest/HEAD, а не создавай дубли вслепую.
