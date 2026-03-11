document.addEventListener('DOMContentLoaded', () => {
    const searchBox = document.getElementById('searchBox');
    const resultsGrid = document.getElementById('resultsGrid');
    const loader = document.getElementById('loader');
    const modal = document.getElementById('modal');
    const loaderModal = document.getElementById('loaderModal');
    const progressContainer = document.getElementById('progressContainer');
    const progressBar = document.getElementById('progressBar');
    const progressText = document.getElementById('progressText');
    const downloadBtn = document.getElementById('downloadBtn');
    const videoPlayer = document.getElementById('videoPlayer');
    const videoPlayerContainer = document.getElementById('videoPlayerContainer');

    let searchTimeout;
    let pollInterval;
    let currentVideoId = null;

    const urlParams = new URLSearchParams(window.location.search);
    const vParam = urlParams.get('v');
    if (vParam) {
        // give modal time to initialize functions below
        setTimeout(() => showDetails('https://www.youtube.com/watch?v=' + vParam), 100);
    }

    searchBox.addEventListener('input', (e) => {
        clearTimeout(searchTimeout);
        const query = e.target.value;
        if (query.length < 3) return;

        searchTimeout = setTimeout(() => performSearch(query), 500);
    });

    async function performSearch(query) {
        loader.style.display = 'block';
        resultsGrid.innerHTML = '';

        try {
            const response = await fetch(`/search?query=${encodeURIComponent(query)}`);
            const videos = await response.json();
            renderResults(videos);
        } catch (error) {
            console.error('Search failed:', error);
        } finally {
            loader.style.display = 'none';
        }
    }

    function renderResults(videos) {
        resultsGrid.innerHTML = videos.map(video => `
            <div class="video-card" onclick="showDetails('${video.url}')">
                <img src="${video.thumbnail}" class="video-thumbnail" alt="${video.title}">
                <div class="video-info">
                    <div class="video-title">${video.title}</div>
                    <div class="video-meta">${video.uploader} • ${video.duration}</div>
                </div>
            </div>
        `).join('');
    }

    window.showDetails = async (url) => {
        modal.style.display = 'flex';
        loaderModal.style.display = 'block';
        progressContainer.style.display = 'none';
        downloadBtn.style.display = 'none';

        try {
            videoPlayer.pause();
            videoPlayer.src = '';
            videoPlayerContainer.style.display = 'none';

            const response = await fetch(`/details?url=${encodeURIComponent(url)}`);
            const details = await response.json();

            currentVideoId = details.info.id;
            updateCacheUI(url, currentVideoId, details.info.title, details.cacheInfo, details.formatId);
        } catch (error) {
            console.error('Failed to load details', error);
        }
    };

    function updateCacheUI(url, videoId, title, cacheInfo, formatId) {
        clearInterval(pollInterval);
        loaderModal.style.display = 'none';
        progressContainer.style.display = 'none';
        downloadBtn.style.display = 'none';

        if (cacheInfo.status !== 'CACHED') {
            videoPlayerContainer.style.display = 'none';
            videoPlayer.pause();
            videoPlayer.src = '';
        }

        if (cacheInfo.status === 'NONE') {
            startCaching(url, videoId, title, formatId);
        } else if (cacheInfo.status === 'DOWNLOADING') {
            progressContainer.style.display = 'block';
            progressBar.style.width = `${cacheInfo.progress}%`;
            progressText.innerText = `${cacheInfo.progress.toFixed(1)}%`;
            pollCacheStatus(url, videoId, title);
        } else if (cacheInfo.status === 'CACHED') {
            downloadBtn.style.display = 'block';
            downloadBtn.onclick = () => {
                window.location.href = `/downloadCached?videoId=${encodeURIComponent(videoId)}&filename=${encodeURIComponent(title + '.mp4')}`;
            };
            
            if (videoPlayer.src.indexOf(`/stream?videoId=${encodeURIComponent(videoId)}`) === -1) {
                videoPlayer.src = `/stream?videoId=${encodeURIComponent(videoId)}`;
                videoPlayerContainer.style.display = 'block';
            }
        }
    }

    async function startCaching(url, videoId, title, formatId) {
        progressContainer.style.display = 'block';
        progressBar.style.width = `0%`;
        progressText.innerText = `0.0%`;

        try {
            await fetch(`/cache/start?url=${encodeURIComponent(url)}&videoId=${encodeURIComponent(videoId)}&formatId=${encodeURIComponent(formatId)}`, { method: 'POST' });
            pollCacheStatus(url, videoId, title);
        } catch (error) {
            console.error('Failed to start cache', error);
            progressContainer.style.display = 'none';
        }
    }

    function pollCacheStatus(url, videoId, title) {
        clearInterval(pollInterval);
        pollInterval = setInterval(async () => {
            try {
                const response = await fetch(`/cache/status?videoId=${encodeURIComponent(videoId)}`);
                const cacheInfo = await response.json();
                updateCacheUI(url, videoId, title, cacheInfo);
            } catch (error) {
                console.error("Failed polling", error);
            }
        }, 1000);
    }

    window.onclick = async (event) => {
        if (event.target === modal) {
            modal.style.display = 'none';
            videoPlayer.pause();
            videoPlayer.src = '';
            clearInterval(pollInterval);
            if (currentVideoId) {
                try {
                    await fetch(`/cache/cancel?videoId=${encodeURIComponent(currentVideoId)}`, { method: 'POST' });
                } catch (e) { console.error("Cancel failed", e); }
                currentVideoId = null;
            }
        }
    };
});
