(platform => {
  const text = e => e?.textContent?.trim() || '';
  const all = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const headings = all('h1,h2,h3,h4,[role="heading"]');
  let cards = [], root = null;
  if (platform === 'start') {
    cards = all('a[class*="Search_search__vline__card_link"]').map(a => ({
      url: a.href, title: a.title.replace(/ смотреть онлайн$/, '')
    }));
  } else if (platform === 'ivi') {
    cards = all('.searchBlock:not(.searchBlock_recommendation) .searchBlock__searchResultItem a[data-content-id]').map(a => ({
      url: a.href, title: a.querySelector('img[alt]')?.alt,
      yearText: a.innerText.match(/\b(?:18|19|20)\d{2}\b/)?.[0]
    }));
  } else if (platform === 'kion') {
    root = headings.find(e => text(e) === 'Фильмы и сериалы');
    while (root && !root.querySelector('a.movie-poster-item')) root = root.parentElement;
    cards = root ? all('a.movie-poster-item', root).map(a => ({
      url: a.href, title: text(a.querySelector('h3.title')),
      availabilityText: text(a.querySelector('.status'))
    })) : [];
  } else if (platform === 'premier') {
    cards = all('.w-search-results a[data-test-id="e-poster"]').map(a => ({
      url: a.href, title: text(a.querySelector('[data-test-id="title"]'))
    }));
  } else if (platform === 'kinopoisk') {
    cards = all('a[class*="base-movie-main-info_link"]').map(a => {
      const card = a.closest('[class*="styles_content__"]');
      const controls = card ? all('button,a', card).map(text) : [];
      const watch = controls.some(t => t === 'Смотреть');
      const trailer = controls.some(t => t === 'Трейлер');
      return {url: a.href,
        title: text(a.querySelector('[class*="desktop-list-main-info_mainTitle"]')),
        yearText: text(a.querySelector('[class*="desktop-list-main-info_secondaryText"]')),
        availability: watch ? 'watch' : trailer ? 'trailer' : 'catalog',
        availabilityText: watch ? 'Смотреть' : trailer ? 'Трейлер' : 'Карточка в базе Кинопоиска'};
    });
  }
  return {url: location.href, input: document.querySelector('input[type="search"]')?.value,
    text: document.body.innerText.slice(0,18000), cards: cards.slice(0,50)};
})
