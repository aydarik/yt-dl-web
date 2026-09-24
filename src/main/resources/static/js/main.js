/* ─── YT-DL main.js ─────────────────────────────────────────── */
(() => {
'use strict';

// ── DOM refs ────────────────────────────────────────────────────
const searchBox       = document.getElementById('searchBox');
const searchClear     = document.getElementById('searchClear');
const searchSubmit    = document.getElementById('searchSubmit');
const loader          = document.getElementById('loader');
const resultsGrid     = document.getElementById('resultsGrid');
const modal           = document.getElementById('modal');
const modalClose      = document.getElementById('modalClose');
const modalTitle      = document.getElementById('modalTitle');
const modalMeta       = document.getElementById('modalMeta');
const playerWrap      = document.getElementById('playerWrap');
const videoPlayer     = document.getElementById('videoPlayer');
const progressSection = document.getElementById('progressSection');
const progressLabel   = document.getElementById('progressLabel');
const progressPct     = document.getElementById('progressPct');
const progressFill    = document.getElementById('progressFill');
const formatSection   = document.getElementById('formatSection');
const formatGrid      = document.getElementById('formatGrid');
const actionRow       = document.getElementById('actionRow');

// ── State ────────────────────────────────────────────────────────
let pollTimer      = null;
let currentVideoId = null;
let currentUrl     = null;
let currentTitle   = null;
let selectedFormat = null;  // { id, label, formatSpec, sortSpec, type, audioOnly }
let isAudioOnly    = false;

// ── Helpers ──────────────────────────────────────────────────────
const isYouTubeUrl = (s) =>
    /^https?:\/\/(www\.)?(youtube\.com\/(watch|shorts)|youtu\.be\/)/.test(s.trim());

function toast(msg, type = 'info', duration = 3500) {
    const container = document.getElementById('toast-container');
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    const icons = { success: '✅', error: '❌', info: 'ℹ️' };
    el.innerHTML = `<span>${icons[type] || ''}</span><span>${msg}</span>`;
    container.appendChild(el);
    setTimeout(() => {
        el.classList.add('fadeout');
        el.addEventListener('animationend', () => el.remove());
    }, duration);
}

function show(el)   { el.classList.add('visible'); }
function hide(el)   { el.classList.remove('visible'); }

// ── Search ───────────────────────────────────────────────────────
function triggerSearch() {
    const q = searchBox.value.trim();
    if (!q) return;
    if (isYouTubeUrl(q)) { openModal(q); return; }
    if (q.length >= 3) performSearch(q);
}

searchBox.addEventListener('input', () => {
    const q = searchBox.value.trim();
    searchClear.classList.toggle('visible', q.length > 0);

    // Immediately open modal for YouTube URLs — no button press needed
    if (isYouTubeUrl(q)) {
        openModal(q);
    }
});

searchBox.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') triggerSearch();
});

searchSubmit.addEventListener('click', triggerSearch);

searchClear.addEventListener('click', () => {
    searchBox.value = '';
    searchClear.classList.remove('visible');
    resultsGrid.innerHTML = '';
    searchBox.focus();
});

async function performSearch(query) {
    loader.classList.add('visible');
    resultsGrid.innerHTML = '';

    try {
        const res = await fetch(`/search?query=${encodeURIComponent(query)}`);
        if (!res.ok) throw new Error('Search failed');
        const videos = await res.json();

        if (!videos.length) {
            resultsGrid.innerHTML = `
                <div class="state-msg">
                    <div class="emoji">🎵</div>
                    <p>No results found for "<strong>${escapeHtml(query)}</strong>"</p>
                </div>`;
            return;
        }

        resultsGrid.innerHTML = '';
        videos.forEach((v, i) => {
            const card = buildCard(v, i);
            resultsGrid.appendChild(card);
        });
    } catch (err) {
        resultsGrid.innerHTML = `
            <div class="state-msg">
                <div class="emoji">⚠️</div>
                <p>Search failed. Check your connection and try again.</p>
            </div>`;
    } finally {
        loader.classList.remove('visible');
    }
}

function buildCard(v, index) {
    const card = document.createElement('div');
    card.className = 'video-card';
    card.style.animationDelay = `${index * 0.05}s`;
    card.setAttribute('role', 'button');
    card.setAttribute('tabindex', '0');
    card.setAttribute('aria-label', v.title);
    card.innerHTML = `
        <div class="card-thumb-wrap">
            <img src="${v.thumbnail}" alt="${escapeHtml(v.title)}" loading="lazy">
            <span class="card-duration">${v.duration}</span>
        </div>
        <div class="card-body">
            <div class="card-title">${escapeHtml(v.title)}</div>
            <div class="card-meta">
                <span>${escapeHtml(v.uploader)}</span>
                ${v.viewCount ? `<span class="dot">·</span><span>${v.viewCount}</span>` : ''}
            </div>
        </div>`;
    card.addEventListener('click', () => openModal(v.url, v));
    card.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') openModal(v.url, v); });
    return card;
}

// ── Modal ────────────────────────────────────────────────────────
let openModalSeq = 0;

function extractVideoId(urlOrId) {
    if (!urlOrId) return null;
    const s = urlOrId.trim();
    if (/^[a-zA-Z0-9_-]{1,64}$/.test(s) && !s.includes('.') && !s.includes('/') && !s.includes('?')) {
        return s;
    }
    try {
        const u = new URL(s.startsWith('http') ? s : `https://${s}`);
        if (u.searchParams.has('v')) return u.searchParams.get('v');
        const parts = u.pathname.split('/').filter(Boolean);
        if (u.hostname.includes('youtu.be')) return parts[0] || null;
        const shortsIdx = parts.indexOf('shorts');
        if (shortsIdx !== -1 && parts[shortsIdx + 1]) return parts[shortsIdx + 1];
        const embedIdx = parts.indexOf('embed');
        if (embedIdx !== -1 && parts[embedIdx + 1]) return parts[embedIdx + 1];
    } catch {
        const m = s.match(/(?:v=|\/shorts\/|\/embed\/|youtu\.be\/)([a-zA-Z0-9_-]{1,64})/);
        if (m) return m[1];
    }
    return null;
}

async function openModal(url, preloadedInfo = null) {
    const seq = ++openModalSeq;
    currentUrl     = url;
    currentVideoId = preloadedInfo?.id || extractVideoId(url);
    currentTitle   = preloadedInfo?.title || null;
    selectedFormat = null;
    isAudioOnly    = false;

    // Reset UI
    modal.classList.add('open');
    document.body.style.overflow = 'hidden';

    hide(playerWrap);
    hide(formatSection);
    videoPlayer.pause();
    videoPlayer.src = '';
    formatGrid.innerHTML = '';
    actionRow.innerHTML  = '';

    if (preloadedInfo) {
        modalTitle.textContent = preloadedInfo.title;
        modalMeta.innerHTML = buildMeta(preloadedInfo);
    } else {
        modalTitle.textContent = 'Loading…';
        modalMeta.innerHTML    = '';
    }

    // Check cache status immediately if video ID is known
    if (currentVideoId) {
        try {
            const cacheRes = await fetch(`/cache/status?videoId=${encodeURIComponent(currentVideoId)}`);
            if (seq !== openModalSeq) return;
            if (cacheRes.ok) {
                const cache = await cacheRes.json();
                if (cache.status === 'CACHED') {
                    isAudioOnly = cache.ext === 'mp3';
                    hide(progressSection);
                    hide(formatSection);
                    renderCachedActions({ id: currentVideoId, title: currentTitle || currentVideoId }, cache);
                    fetchBackgroundDetailsAndFormats(url, currentVideoId, seq, true);
                    return;
                } else if (cache.status === 'DOWNLOADING') {
                    isAudioOnly = cache.ext === 'mp3';
                    hide(formatSection);
                    show(progressSection);
                    const label = cache.ext ? ` ${cache.ext.toUpperCase()}` : '';
                    progressLabel.textContent = `Downloading${label}…`;
                    setProgress(cache.progress || 0);
                    renderCancelButton();
                    pollStatus();
                    fetchBackgroundDetailsAndFormats(url, currentVideoId, seq, true);
                    return;
                }
            }
        } catch (e) {
            console.warn('Quick cache check failed, falling back to full details', e);
        }
    }

    // Video is not cached (or videoId unknown) — show loading state and fetch video info
    show(progressSection);
    progressLabel.textContent = 'Fetching video info…';
    progressPct.textContent   = '';
    progressFill.style.width  = '0%';

    try {
        const res = await fetch(`/details?url=${encodeURIComponent(url)}`);
        if (seq !== openModalSeq) return;
        if (!res.ok) throw new Error('details fetch failed');
        const details = await res.json();
        if (!details) throw new Error('no details returned');

        const info  = details.info;
        const cache = details.cacheInfo;

        currentVideoId = info.id;
        currentTitle   = info.title;

        modalTitle.textContent = info.title;
        modalMeta.innerHTML = buildMeta(info);

        if (cache && cache.status === 'CACHED') {
            isAudioOnly = cache.ext === 'mp3';
            hide(progressSection);
            hide(formatSection);
            renderCachedActions(info, cache);
            loadFormats(url, info, cache, true);
        } else if (cache && cache.status === 'DOWNLOADING') {
            isAudioOnly = cache.ext === 'mp3';
            hide(formatSection);
            show(progressSection);
            const label = cache.ext ? ` ${cache.ext.toUpperCase()}` : '';
            progressLabel.textContent = `Downloading${label}…`;
            setProgress(cache.progress || 0);
            renderCancelButton();
            pollStatus();
            loadFormats(url, info, cache, true);
        } else {
            loadFormats(url, info, cache, false);
        }

    } catch (err) {
        if (seq !== openModalSeq) return;
        progressLabel.textContent = '⚠️ Failed to fetch video info.';
        progressPct.textContent   = '';
        actionRow.innerHTML = `<button class="btn btn-ghost" id="closeFromErr">Close</button>`;
        document.getElementById('closeFromErr').onclick = closeModal;
        toast('Could not load video details', 'error');
    }
}

async function fetchBackgroundDetailsAndFormats(url, videoId, seq, skipReady = false) {
    try {
        if (!currentTitle) {
            const res = await fetch(`/details?url=${encodeURIComponent(url)}`);
            if (seq === openModalSeq && res.ok) {
                const details = await res.json();
                if (details?.info) {
                    currentTitle = details.info.title;
                    modalTitle.textContent = details.info.title;
                    modalMeta.innerHTML = buildMeta(details.info);
                    updateDownloadButtonFilenames(details.info.title);
                }
            }
        }
        loadFormats(url, { id: videoId, title: currentTitle || videoId }, null, skipReady);
    } catch {
        /* background fetch failure is non-fatal */
    }
}

function updateDownloadButtonFilenames(title) {
    const dlBtns = actionRow.querySelectorAll('a[data-ext]');
    dlBtns.forEach(btn => {
        const ext = btn.dataset.ext;
        const filename = `${title}.${ext}`;
        btn.href = `/download?videoId=${encodeURIComponent(currentVideoId)}&filename=${encodeURIComponent(filename)}`;
        btn.setAttribute('download', filename);
    });
}

async function loadFormats(url, info, cache, skipReady = false) {
    try {
        const res = await fetch(`/formats`);
        const formats = res.ok ? await res.json() : defaultFormats();
        if (!skipReady) {
            renderModalReady(info, cache, formats);
        } else {
            populateFormatGrid(formats);
        }
    } catch {
        if (!skipReady) {
            renderModalReady(info, cache, defaultFormats());
        } else {
            populateFormatGrid(defaultFormats());
        }
    }
}

function defaultFormats() {
    return [
        { id: 'video_1080p', label: '1080p HD Video', quality: '1080p', type: 'video', formatSpec: 'bv*[height<=1080]+ba/b[height<=1080]/b', sortSpec: 'res:1080,ext:mp4:m4a' },
        { id: 'video_720p',  label: '720p HD Video',  quality: '720p',  type: 'video', formatSpec: 'bv*[height<=720]+ba/b[height<=720]/b',   sortSpec: 'res:720,ext:mp4:m4a' },
        { id: 'video_480p',  label: '480p Video',     quality: '480p',  type: 'video', formatSpec: 'bv*[height<=480]+ba/b[height<=480]/b',   sortSpec: 'res:480,ext:mp4:m4a' },
        { id: 'video_360p',  label: '360p Video',     quality: '360p',  type: 'video', formatSpec: 'bv*[height<=360]+ba/b[height<=360]/b',   sortSpec: 'res:360,ext:mp4:m4a' },
        { id: 'audio_mp3',   label: 'Audio Only (MP3)', quality: '128k', type: 'audio', formatSpec: 'ba[acodec^=mp3]/ba/b', sortSpec: '' },
        { id: 'audio_m4a',   label: 'Audio Only (M4A)', quality: 'best', type: 'audio', formatSpec: 'ba[ext=m4a]/ba/b',     sortSpec: '' },
    ];
}

function populateFormatGrid(formats) {
    formatGrid.innerHTML = '';
    const defaultId = 'video_720p';
    formats.forEach(fmt => {
        const btn = document.createElement('button');
        btn.className = `format-btn${fmt.type === 'audio' ? ' audio-btn' : ''}`;
        btn.dataset.fmtId = fmt.id;
        btn.innerHTML = `
            <span class="format-name">${escapeHtml(fmt.label)}</span>
            <span class="format-quality">${escapeHtml(fmt.quality)}</span>
            <span class="format-type-badge">${fmt.type === 'audio' ? '🎵 Audio' : '🎬 Video'}</span>`;
        btn.addEventListener('click', () => selectFormat(fmt, formats));
        formatGrid.appendChild(btn);

        if (fmt.id === defaultId) {
            selectFormat(fmt, formats);
        }
    });
    if (!selectedFormat && formats.length) selectFormat(formats[0], formats);
}

function renderModalReady(info, cache, formats) {
    if (!modal.classList.contains('open')) return; // Modal was closed while loading

    populateFormatGrid(formats);

    // Check cache status and update UI
    if (cache && cache.status === 'CACHED') {
        onCached(info, cache);
    } else if (cache && cache.status === 'DOWNLOADING') {
        hide(formatSection);
        show(progressSection);
        progressLabel.textContent = 'Download in progress…';
        setProgress(cache.progress || 0);
        renderCancelButton();
        pollStatus();
    } else if (cache && cache.status === 'FAILED') {
        hide(progressSection);
        show(formatSection);
        renderDownloadButton(info);
    } else {
        // NONE — show format picker + Download button
        hide(progressSection);
        show(formatSection);
        renderDownloadButton(info);
    }
}

function selectFormat(fmt, allFormats) {
    selectedFormat = fmt;
    isAudioOnly = fmt.type === 'audio';

    // Update button states
    allFormats.forEach(f => {
        const btn = formatGrid.querySelector(`[data-fmt-id="${f.id}"]`);
        if (btn) btn.classList.toggle('selected', f.id === fmt.id);
    });
}

function buildMeta(info) {
    const parts = [];
    if (info.uploader) parts.push(`<span>👤 ${escapeHtml(info.uploader)}</span>`);
    if (info.duration)  parts.push(`<span>⏱ ${escapeHtml(info.duration)}</span>`);
    if (info.viewCount) parts.push(`<span>👁 ${escapeHtml(info.viewCount)}</span>`);
    return parts.join('');
}

// ── Actions ──────────────────────────────────────────────────────
function renderDownloadButton(info) {
    actionRow.innerHTML = '';
    const btn = document.createElement('button');
    btn.className = 'btn btn-primary';
    btn.id = 'downloadStartBtn';
    btn.innerHTML = '⬇ Download';
    btn.addEventListener('click', () => startDownload(info));
    actionRow.appendChild(btn);
}

function renderCancelButton() {
    // Ensure cancel button is in actionRow
    if (!document.getElementById('cancelBtn')) {
        actionRow.innerHTML = '';
        const btn = document.createElement('button');
        btn.className = 'btn btn-ghost';
        btn.id = 'cancelBtn';
        btn.innerHTML = '✕ Cancel';
        btn.addEventListener('click', cancelDownload);
        actionRow.appendChild(btn);
    }
}

function renderCachedActions(info, cache = null) {
    actionRow.innerHTML = '';

    const hasMp4 = cache ? (cache.hasMp4 || cache.ext === 'mp4') : !isAudioOnly;
    const hasMp3 = cache ? (cache.hasMp3 || cache.ext === 'mp3') : isAudioOnly;

    // Play button
    const playBtn = document.createElement('button');
    playBtn.className = 'btn btn-primary';
    playBtn.id = 'playBtn';
    playBtn.innerHTML = '▶ Play';
    playBtn.addEventListener('click', () => {
        videoPlayer.src = `/stream?videoId=${encodeURIComponent(info.id)}`;
        show(playerWrap);
        videoPlayer.play();
        playBtn.style.display = 'none';
    });
    actionRow.appendChild(playBtn);

    // Save MP4 button
    if (hasMp4 || !hasMp3) {
        const filenameMp4 = (currentTitle || info.title || info.id) + '.mp4';
        const dlMp4Btn = document.createElement('a');
        dlMp4Btn.className = 'btn btn-secondary';
        dlMp4Btn.id = 'downloadFileBtn';
        dlMp4Btn.dataset.ext = 'mp4';
        dlMp4Btn.href = `/download?videoId=${encodeURIComponent(info.id)}&filename=${encodeURIComponent(filenameMp4)}`;
        dlMp4Btn.setAttribute('download', filenameMp4);
        dlMp4Btn.innerHTML = '⬇ Save MP4';
        actionRow.appendChild(dlMp4Btn);
    }

    // Save MP3 button
    if (hasMp3) {
        const filenameMp3 = (currentTitle || info.title || info.id) + '.mp3';
        const dlMp3Btn = document.createElement('a');
        dlMp3Btn.className = 'btn btn-secondary';
        dlMp3Btn.id = 'downloadMp3Btn';
        dlMp3Btn.dataset.ext = 'mp3';
        dlMp3Btn.href = `/download?videoId=${encodeURIComponent(info.id)}&filename=${encodeURIComponent(filenameMp3)}`;
        dlMp3Btn.setAttribute('download', filenameMp3);
        dlMp3Btn.innerHTML = '⬇ Save MP3';
        actionRow.appendChild(dlMp3Btn);
    }

    const retryBtn = document.createElement('button');
    retryBtn.className = 'btn btn-ghost';
    retryBtn.id = 'retryBtn';
    retryBtn.title = 'Download again with a different format';
    retryBtn.innerHTML = '↺ Re-download';
    retryBtn.addEventListener('click', () => {
        hide(playerWrap);
        videoPlayer.pause();
        videoPlayer.src = '';
        hide(progressSection);
        show(formatSection);
        renderDownloadButton(info);
    });
    actionRow.appendChild(retryBtn);
}

async function startDownload(info) {
    if (!selectedFormat) { toast('Please select a quality', 'info'); return; }

    const btn = document.getElementById('downloadStartBtn');
    if (btn) { btn.disabled = true; btn.textContent = 'Starting…'; }

    const params = new URLSearchParams({
        url:        currentUrl,
        videoId:    info.id,
        formatSpec: selectedFormat.formatSpec,
        sortSpec:   selectedFormat.sortSpec,
        audioOnly:  String(selectedFormat.type === 'audio'),
    });

    try {
        const res = await fetch(`/cache/start?${params}`, { method: 'POST' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        // Show progress UI
        hide(formatSection);
        show(progressSection);
        progressLabel.textContent = `Downloading ${selectedFormat.label}…`;
        setProgress(0);
        renderCancelButton();
        pollStatus();

    } catch (err) {
        toast('Failed to start download', 'error');
        if (btn) { btn.disabled = false; btn.innerHTML = '⬇ Download'; }
    }
}

async function cancelDownload() {
    if (!currentVideoId) return;
    clearInterval(pollTimer);
    try {
        await fetch(`/cache/cancel?videoId=${encodeURIComponent(currentVideoId)}`, { method: 'POST' });
    } catch { /* ignore */ }

    videoPlayer.pause();
    videoPlayer.src = '';
    hide(playerWrap);
    hide(progressSection);
    show(formatSection);
    renderDownloadButton({ id: currentVideoId, title: currentTitle });
    toast('Download cancelled', 'info');
}

// ── Status polling ────────────────────────────────────────────────
function pollStatus() {
    clearInterval(pollTimer);
    pollTimer = setInterval(async () => {
        if (!currentVideoId) { clearInterval(pollTimer); return; }
        try {
            const res = await fetch(`/cache/status?videoId=${encodeURIComponent(currentVideoId)}`);
            if (!res.ok) return;
            const status = await res.json();
            handleStatus(status);
        } catch { /* network hiccup — keep polling */ }
    }, 1000);
}

function handleStatus(cache) {
    if (cache.status === 'DOWNLOADING') {
        setProgress(cache.progress);
        const label = selectedFormat ? selectedFormat.label : (cache.ext ? cache.ext.toUpperCase() : '');
        progressLabel.textContent = `Downloading${label ? ' ' + label : ''}…`;
    } else if (cache.status === 'CACHED') {
        clearInterval(pollTimer);
        isAudioOnly = cache.ext === 'mp3' || isAudioOnly;
        onCached({ id: currentVideoId, title: currentTitle }, cache, true);
    } else if (cache.status === 'FAILED') {
        clearInterval(pollTimer);
        progressLabel.innerHTML = '⚠️ Download failed. Try again.';
        progressPct.textContent = '';
        hide(progressSection);
        show(formatSection);
        renderDownloadButton({ id: currentVideoId, title: currentTitle });
        toast('Download failed', 'error');
    }
}

function onCached(info, cache = null, showToast = false) {
    clearInterval(pollTimer);
    hide(progressSection);
    hide(formatSection);
    renderCachedActions(info, cache);
    if (showToast) {
        toast(`${isAudioOnly ? 'Audio' : 'Video'} ready!`, 'success');
    }
}

function setProgress(pct) {
    const p = Math.min(100, Math.max(0, pct));
    progressFill.style.width = `${p}%`;
    progressPct.textContent  = `${p.toFixed(1)}%`;
}

// ── Modal close ───────────────────────────────────────────────────
function closeModal() {
    modal.classList.remove('open');
    document.body.style.overflow = '';
    clearInterval(pollTimer);
    videoPlayer.pause();
    videoPlayer.src = '';
    hide(playerWrap);

    currentVideoId = null;
    currentUrl     = null;
    currentTitle   = null;
    selectedFormat = null;
    isAudioOnly    = false;
}

modalClose.addEventListener('click', closeModal);
modal.addEventListener('click', (e) => { if (e.target === modal) closeModal(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && modal.classList.contains('open')) closeModal(); });

// ── URL param ?v=... ──────────────────────────────────────────────
const vParam = new URLSearchParams(location.search).get('v');
if (vParam) {
    setTimeout(() => openModal('https://www.youtube.com/watch?v=' + encodeURIComponent(vParam)), 80);
}

// ── Escape helper ─────────────────────────────────────────────────
function escapeHtml(s) {
    if (!s) return '';
    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
            .replace(/"/g,'&quot;').replace(/'/g,'&#039;');
}

})();
