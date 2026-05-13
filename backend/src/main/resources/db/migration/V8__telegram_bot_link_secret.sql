alter table telegram_bot_bindings
    add column if not exists link_secret_hash text;
