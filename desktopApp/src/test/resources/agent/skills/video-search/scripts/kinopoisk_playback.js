// Public catalogue metadata only: never return cookies, account state or player streams.
(catalogId => {
  const page = {url: location.href, title: document.querySelector('h1')?.textContent?.trim(),
    blocked: /showcaptcha|captcha|auth\.kinopoisk/.test(location.href), contentId: null};
  let next = {};
  try { next = JSON.parse(document.querySelector('#__NEXT_DATA__')?.textContent || '{}'); } catch (_) {}
  const states = [window.Ya?.__ssr_initial_data?.apolloState, next.props?.apolloState?.data,
    next.props?.pageProps?.apolloState?.data].filter(Boolean);
  const hex = x => typeof x === 'string' && /^[a-f0-9]{32}$/.test(x);
  if (catalogId) {
    const objects = states.flatMap(s => Object.values(s)).filter(x => x &&
      String(x.id) === catalogId && ['Film', 'TvSeries'].includes(x.__typename) && hex(x.contentId));
    const ids = [...new Set(objects.map(x => x.contentId))];
    if (ids.length === 1) {
      page.contentId = ids[0];
      page.series = objects.some(x => x.__typename === 'TvSeries') || location.pathname.startsWith('/series/');
      page.title ||= objects[0].title?.localized || objects[0].title?.russian;
    } else if (ids.length === 0) {
      // Older series pages expose the selected title's explicit “choose episode” link.
      const links = Array.from(document.querySelectorAll('a[href]')).filter(a =>
        /^(Выбрать серию|Смотреть|Смотреть фильм|Смотреть сериал)$/.test(a.textContent.trim()));
      const candidates = [...new Set(links.map(a => {
        const u = new URL(a.href);
        return u.protocol === 'https:' && u.hostname === 'hd.kinopoisk.ru' &&
          /^\/film\/[a-f0-9]{32}\/?$/.test(u.pathname) ? u.pathname.split('/')[2] : null;
      }).filter(Boolean))];
      if (candidates.length === 1) page.contentId = candidates[0];
      page.series = location.pathname.startsWith('/series/');
    }
  } else {
    const id = location.pathname.match(/\/film\/([a-f0-9]{32})\/?$/)?.[1];
    const state = states.find(s => s['__Movie:' + id]);
    const movie = state?.['__Movie:' + id];
    if (movie?.contentId === id) {
      page.contentId = id;
      page.title ||= movie.title?.localized;
      const resolve = x => x?.__ref ? state[x.__ref] : x;
      const seasons = Object.entries(movie).filter(([k,v]) => k.startsWith('seasons(') && v?.items)
        .flatMap(([k,v]) => v.items.map(resolve)).filter(Boolean);
      const unique = [...new Map(seasons.map(s => [s.contentId,s])).values()];
      page.seasons = unique.map(s => ({id: s.contentId, number: s.number,
        episodes: Object.values(state).filter(e => e?.__typename === 'Episode' &&
          resolve(e.season)?.contentId === s.contentId).map(e => ({id: e.contentId,
            number: e.number, status: e.viewOption?.watchabilityStatus ?? null}))}));
    }
  }
  return page;
})
