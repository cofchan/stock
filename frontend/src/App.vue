<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

const overview = ref({ indexes: [], movers: [] })
const watchlist = ref([])
const market = ref('all')
const selected = ref(null)
const range = ref('1M')
const history = ref([])
const dividends = ref([])
const historyLoading = ref(false)
const historyError = ref('')
const hoverPoint = ref(null)
const hoverX = ref(0)
const hoverY = ref(0)
const loading = ref(true)
const searchQuery = ref('')
const searchResults = ref(null)
const searching = ref(false)
const searchError = ref('')
const watchlistStorageKey = 'stock-dashboard-watchlist'
let refreshTimer

const filteredQuotes = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  const quotes = searchResults.value ?? watchlist.value
  return quotes.filter(q => {
    const matchesMarket = market.value === 'all' || q.market === market.value
    const matchesSearch = !query || q.symbol.toLowerCase().includes(query) || q.name.toLowerCase().includes(query)
    return matchesMarket && matchesSearch
  })
})
const showingSearchResults = computed(() => searchResults.value !== null)
const maxClose = computed(() => history.value.length ? Math.max(...history.value.map(p => p.close)) : 1)
const minClose = computed(() => history.value.length ? Math.min(...history.value.map(p => p.close)) : 0)
const chartPoints = computed(() => {
  const span = Math.max(maxClose.value - minClose.value, 1)
  return history.value.map((p, i) => `${(i / Math.max(history.value.length - 1, 1)) * 100},${100 - ((p.close - minClose.value) / span) * 80 - 10}`).join(' ')
})
const chartLabels = computed(() => {
  if (!history.value.length) return []
  const points = [history.value[0], history.value[Math.floor((history.value.length - 1) / 2)], history.value[history.value.length - 1]]
  return points.map(point => {
    const date = new Date(point.date)
    if (range.value === '1D') return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    return date.toLocaleDateString([], { month: 'short', day: '2-digit' })
  })
})
function formatPointDate(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return range.value === '1D'
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString([], { year: 'numeric', month: 'short', day: '2-digit' })
}
function updateChartHover(event) {
  if (!history.value.length) return
  const bounds = event.currentTarget.getBoundingClientRect()
  const ratio = Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width))
  const index = Math.round(ratio * (history.value.length - 1))
  const point = history.value[index]
  const span = Math.max(maxClose.value - minClose.value, 1)
  hoverX.value = (index / Math.max(history.value.length - 1, 1)) * 100
  hoverY.value = 100 - ((point.close - minClose.value) / span) * 80 - 10
  hoverPoint.value = point
}
function clearChartHover() {
  hoverPoint.value = null
}

async function loadOverview() {
  loading.value = true
  overview.value = await fetch('/api/overview').then(r => r.json())
  const liveQuotes = new Map(overview.value.movers.map(quote => [quote.symbol, quote]))
  let storedWatchlist = null
  try {
    const storedValue = localStorage.getItem(watchlistStorageKey)
    storedWatchlist = storedValue ? JSON.parse(storedValue) : null
  } catch (error) {
    storedWatchlist = null
  }
  const savedQuotes = Array.isArray(storedWatchlist)
    ? storedWatchlist.map(quote => liveQuotes.get(quote.symbol) ?? quote)
    : overview.value.movers
  const refreshedQuotes = await Promise.all(savedQuotes.map(async quote => {
    try {
      const response = await fetch(`/api/quotes/${encodeURIComponent(quote.symbol)}`)
      return response.ok ? await response.json() : quote
    } catch (error) {
      return quote
    }
  }))
  watchlist.value = refreshedQuotes
  persistWatchlist()
  if (!selected.value) {
    await selectQuote(watchlist.value[0] ?? overview.value.movers[0])
  } else {
    const refreshedSelected = watchlist.value.find(quote => quote.symbol === selected.value.symbol)
    if (refreshedSelected) await selectQuote(refreshedSelected)
  }
  loading.value = false
}
function persistWatchlist() {
  localStorage.setItem(watchlistStorageKey, JSON.stringify(watchlist.value))
}
function isInWatchlist(symbol) {
  return watchlist.value.some(quote => quote.symbol === symbol)
}
function toggleWatchlist(quote) {
  watchlist.value = isInWatchlist(quote.symbol)
    ? watchlist.value.filter(item => item.symbol !== quote.symbol)
    : [...watchlist.value, quote]
  persistWatchlist()
}
async function selectQuote(quote) {
  if (!quote) return
  selected.value = quote
  hoverPoint.value = null
  historyLoading.value = true
  historyError.value = ''
  const symbol = encodeURIComponent(quote.symbol)
  try {
    const [historyResponse, dividendsResponse] = await Promise.all([
      fetch(`/api/quotes/${symbol}/history?range=${range.value}`),
      fetch(`/api/quotes/${symbol}/dividends`)
    ])
    if (!historyResponse.ok || !dividendsResponse.ok) throw new Error('Market data request failed')
    ;[history.value, dividends.value] = await Promise.all([
      historyResponse.json(),
      dividendsResponse.json()
    ])
    if (!history.value.length) historyError.value = 'Price history is temporarily unavailable.'
  } catch (error) {
    history.value = []
    dividends.value = []
    historyError.value = 'Price history is temporarily unavailable. Try refreshing.'
  } finally {
    historyLoading.value = false
  }
}
async function searchStock() {
  const query = searchQuery.value.trim()
  if (!query) {
    searchResults.value = null
    searchError.value = ''
    return
  }
  searching.value = true
  searchError.value = ''
  try {
    const response = await fetch(`/api/search?q=${encodeURIComponent(query)}`)
    if (!response.ok) throw new Error('Search request failed')
    searchResults.value = await response.json()
    if (searchResults.value.length) {
      await selectQuote(filteredQuotes.value[0])
    } else {
      selected.value = null
      history.value = []
      dividends.value = []
    }
  } catch (error) {
    searchError.value = 'Search is temporarily unavailable. Try again.'
  } finally {
    searching.value = false
  }
}
watch(range, () => selected.value && selectQuote(selected.value))
onMounted(() => {
  loadOverview()
  refreshTimer = window.setInterval(loadOverview, 60_000)
})
onUnmounted(() => window.clearInterval(refreshTimer))
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">↗</span><span>stock<span class="accent">.</span></span></div>
      <nav><a class="active">Overview <span>⌂</span></a><a>Watchlist <span>☆</span></a><a>Dividends <span>◒</span></a></nav>
      <div class="sidebar-note"><span class="live-dot"></span> Markets live<br><small>Last updated 2 min ago</small></div>
      <div class="user"><div><strong>My portfolio</strong><small>Personal portfolio</small></div><span>•••</span></div>
    </aside>
    <main class="content">
      <header><div><p class="eyebrow">SATURDAY, NOVEMBER 22, 2025</p><h1>Good morning <span>✦</span></h1><p class="subtle">Here’s what’s happening in your markets today.</p></div><button class="refresh" @click="loadOverview">↻ Refresh</button></header>
      <section class="index-grid">
        <article v-for="index in overview.indexes" :key="index.symbol" class="index-card"><div class="card-top"><span>{{ index.name }}</span><span class="tiny-symbol">{{ index.symbol.replace('^','') }}</span></div><strong>{{ index.value.toLocaleString() }}</strong><span :class="index.change >= 0 ? 'positive' : 'negative'">{{ index.change >= 0 ? '+' : '' }}{{ index.change }} ({{ index.changePercent }}%)</span><div class="sparkline" :class="index.change >= 0 ? 'up' : 'down'"><i></i><i></i><i></i><i></i><i></i><i></i></div></article>
      </section>
      <div class="section-heading"><div><h2>Market watch</h2><p class="subtle">Track your favorite HK and US stocks.</p></div><div class="market-controls"><form class="stock-search" @submit.prevent="searchStock"><label class="sr-only" for="stock-search">Search by stock code or name</label><input id="stock-search" v-model="searchQuery" @input="searchResults = null; searchError = ''" type="search" placeholder="Enter code or name" autocomplete="off"><button type="submit">{{ searching ? 'Searching…' : 'Search' }}</button></form><div class="tabs"><button v-for="tab in [['all','All markets'],['HK','Hong Kong'],['US','United States']]" :key="tab[0]" type="button" :class="{selected: market === tab[0]}" @click="market = tab[0]">{{ tab[1] }}</button></div></div></div>
      <section class="dashboard-grid">
        <article class="panel watchlist"><div class="panel-title"><h3>{{ showingSearchResults ? 'Search results' : 'Watchlist' }}</h3><span class="count">{{ filteredQuotes.length }} symbols</span></div><div v-if="loading" class="empty">Loading market data…</div><div v-else-if="searchError" class="empty">{{ searchError }}</div><div v-else-if="!filteredQuotes.length" class="empty">{{ showingSearchResults ? 'No stocks match “' + searchQuery + '”. Try another code or name.' : 'Your watchlist is empty. Search for a stock to add it.' }}</div><button v-for="quote in filteredQuotes" :key="quote.symbol" class="quote-row" :class="{chosen: selected?.symbol === quote.symbol}" @click="selectQuote(quote)"><span class="company-icon">{{ quote.symbol[0] }}</span><span class="quote-name"><strong>{{ quote.name }}</strong><small>{{ quote.symbol }} · {{ quote.market }}</small></span><span class="quote-price"><strong>{{ quote.price.toFixed(2) }}</strong><small>{{ quote.currency }}</small></span><span :class="quote.change >= 0 ? 'positive' : 'negative'">{{ quote.change >= 0 ? '+' : '' }}{{ quote.changePercent.toFixed(2) }}%</span><span class="watchlist-action" @click.stop="toggleWatchlist(quote)">{{ isInWatchlist(quote.symbol) ? 'Remove' : 'Add' }}</span></button></article>
        <article class="panel detail"><div class="panel-title"><div><h3>{{ selected?.name || 'Select a stock' }}</h3><p class="subtle">{{ selected?.symbol }} · {{ selected?.currency }}</p></div><div class="range-tabs"><button v-for="r in ['1D','1M','1Y']" :key="r" :class="{selected: range === r}" @click="range = r">{{ r }}</button></div></div><div v-if="selected" class="detail-price"><strong>{{ selected.price.toFixed(2) }}</strong><span :class="selected.change >= 0 ? 'positive' : 'negative'">{{ selected.change >= 0 ? '+' : '' }}{{ selected.change.toFixed(2) }} ({{ selected.changePercent.toFixed(2) }}%)</span></div><div v-if="historyLoading" class="chart empty">Loading price history…</div><div v-else-if="historyError" class="chart empty">{{ historyError }}</div><div v-else class="chart" @mousemove="updateChartHover" @mouseleave="clearChartHover"><svg viewBox="0 0 100 100" preserveAspectRatio="none"><defs><linearGradient id="fill" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="#8ce0c1" stop-opacity=".36"/><stop offset="1" stop-color="#8ce0c1" stop-opacity="0"/></linearGradient></defs><polygon :points="`0,100 ${chartPoints} 100,100`" fill="url(#fill)"/><polyline :points="chartPoints" fill="none" stroke="#38b989" stroke-width="1.5" vector-effect="non-scaling-stroke"/><line v-if="hoverPoint" :x1="hoverX" :x2="hoverX" y1="0" y2="100" stroke="#7fa99a" stroke-dasharray="2 2" vector-effect="non-scaling-stroke"/><circle v-if="hoverPoint" :cx="hoverX" :cy="hoverY" r="2" fill="#fff" stroke="#238a66" stroke-width="1" vector-effect="non-scaling-stroke"/></svg><div v-if="hoverPoint" class="chart-tooltip" :style="{left: `${hoverX}%`, top: `${hoverY}%`}"><strong>{{ selected.currency }} {{ hoverPoint.close.toFixed(2) }}</strong><span>{{ formatPointDate(hoverPoint.date) }}</span></div></div><div class="chart-labels"><span v-for="label in chartLabels" :key="label">{{ label }}</span></div></article>
      </section>
      <section class="panel dividends"><div class="panel-title"><div><h3>Dividend history</h3><p class="subtle">Recent payouts for {{ selected?.symbol || 'your selected stock' }}.</p></div><span class="yield" v-if="selected">{{ selected.dividendYield.toFixed(2) }}% yield</span></div><table v-if="dividends.length"><thead><tr><th>Ex-dividend date</th><th>Payment date</th><th>Amount</th><th>Yield</th></tr></thead><tbody><tr v-for="dividend in dividends" :key="dividend.exDate"><td>{{ dividend.exDate }}</td><td>{{ dividend.payDate }}</td><td>{{ selected.currency }} {{ dividend.amount.toFixed(2) }}</td><td class="positive">{{ dividend.yield.toFixed(2) }}%</td></tr></tbody></table><div v-else class="empty">No dividend history is available for this symbol.</div></section>
      <footer><span>Data shown is for demonstration purposes.</span><span>HK · US markets <i class="live-dot"></i></span></footer>
    </main>
  </div>
</template>

<style>
.chart {
  position: relative;
  cursor: crosshair;
}
.chart-tooltip {
  position: absolute;
  transform: translate(-50%, -115%);
  pointer-events: none;
  background: #18332b;
  color: #fff;
  border-radius: 6px;
  padding: 7px 9px;
  white-space: nowrap;
  display: grid;
  gap: 2px;
  box-shadow: 0 3px 10px #18332b33;
  font: 10px 'DM Mono', monospace;
}
.chart-tooltip span {
  color: #b8d0c5;
  font-size: 9px;
}
.watchlist-action {
  border: 1px solid #dbe4df;
  border-radius: 5px;
  background: #fff;
  color: #39705e;
  font-size: 10px;
  padding: 5px 7px;
  min-width: 48px;
  text-align: center;
}
.watchlist-action:hover {
  background: #eaf5ef;
}
</style>
