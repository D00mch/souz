"""Private session persistence and cooperative limits for the Linux Souz sandbox."""
import sys
sys.dont_write_bytecode = True
import fcntl
import hashlib
import json
import os
import tempfile
import time
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from http.cookiejar import MozillaCookieJar, LoadError
from pathlib import Path

class StateError(Exception): pass

def action_for(url):
    from urllib.parse import urlsplit
    path=urlsplit(url).path
    # Never persist or display one-use download tokens.
    for prefix in ('/read/entry/dl/', '/read/cdl/', '/read/dl/', '/book/', '/collection/'):
        if path.startswith(prefix): return prefix.rstrip('/')
    return path if path in ('/','/entry','/search','/auth/new_book_user','/reg') else '/other'

class HttpState:
    def __init__(self, credentials, login, route_id="direct"):
        self.route_id=route_id
        self.root=Path(credentials).resolve().parent/'http'
        if self.root.is_symlink(): raise StateError('Unsafe HTTP state directory')
        self.root.mkdir(mode=0o700,exist_ok=True)
        os.chmod(self.root,0o700)
        fd=os.open(self.root/'session.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
        self.lock=os.fdopen(fd,'a+')
        os.fchmod(self.lock.fileno(),0o600)
        try: fcntl.flock(self.lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:
            self.lock.close()
            raise StateError('Another Librusec command is running; wait for it to finish') from None
        try:
            self.cookie_path=self.root/('session-'+hashlib.sha256(login.encode()).hexdigest()[:20]+'.cookies')
            if self.cookie_path.is_symlink(): raise StateError('Unsafe session file')
            self.cookies=MozillaCookieJar(str(self.cookie_path))
            if self.cookie_path.exists():
                os.chmod(self.cookie_path,0o600)
                try: self.cookies.load(ignore_discard=True,ignore_expires=False)
                except (LoadError,OSError): raise StateError('Session cache cannot be read; inspect it privately') from None
            self.limits_path=self.root/'limits.json'
            if self.limits_path.is_symlink(): raise StateError('Unsafe limits file')
            self.limits=json.loads(self.limits_path.read_text()) if self.limits_path.exists() else {}
        except Exception:
            self.close();raise

    def close(self):
        if not self.lock.closed:
            fcntl.flock(self.lock,fcntl.LOCK_UN);self.lock.close()

    def atomic_write(self,path,write):
        fd,tmp=tempfile.mkstemp(prefix='.http-',dir=self.root)
        os.close(fd)
        try:
            write(tmp)
            os.chmod(tmp,0o600)
            os.replace(tmp,path)
        finally:
            if os.path.exists(tmp): os.unlink(tmp)

    def save(self):
        self.atomic_write(self.cookie_path,lambda p:self.cookies.save(p,ignore_discard=True,ignore_expires=False))
        self.atomic_write(self.limits_path,lambda p:Path(p).write_text(json.dumps(self.limits)))

    def before_request(self,action):
        key=action if self.route_id=='direct' else self.route_id+':'+action
        limit=self.limits.get('cooldowns',{}).get(key,{})
        if limit.get('until',0)>time.time():
            raise StateError("Saved cooldown; "+self.describe(action,limit))
        delay=self.limits.get('nextRequestAt',0)-time.time()
        if delay>0: time.sleep(min(delay,2))
        self.limits['nextRequestAt']=time.time()+1
        self.save()

    def rate_limited(self,action,headers,body):
        now=time.time();retry=headers.get('Retry-After','').strip()
        daily='Daily limit reached' in body or 'Дневной лимит' in body
        until=None
        try:
            until=now+max(0,int(retry))
        except (ValueError,TypeError):
            if retry:
                try: until=parsedate_to_datetime(retry).timestamp()
                except (ValueError,TypeError,OverflowError): pass
        # The site gives no reset time for its daily limit. 24 h is conservative,
        # explicitly a local pause, not a claim about the server's timezone.
        policy='Retry-After' if until is not None else 'local conservative pause; server reset unknown'
        if until is None: until=now+(86400 if daily else 300)
        limit={'until':max(now+1,until),'daily':daily,'policy':policy}
        key=action if self.route_id=='direct' else self.route_id+':'+action
        self.limits.setdefault('cooldowns',{})[key]=limit
        self.save()
        return self.describe(action,limit)

    @staticmethod
    def describe(action,limit):
        until=datetime.fromtimestamp(limit['until'],timezone.utc).strftime('%Y-%m-%d %H:%M UTC')
        kind='daily action limit' if limit.get('daily') else 'rate limit'
        return f'HTTP 429 at {action}: {kind}; paused until {until} ({limit["policy"]}). No request/retry was sent after this response.'
