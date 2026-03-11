document.addEventListener('DOMContentLoaded', () => {
    const searchBox = document.getElementById('searchBox');
    const resultsGrid = document.getElementById('resultsGrid');
    const loader = document.getElementById('loader');
    const modal = document.getElementById('modal');
    const formatList = document.getElementById('formatList');
    const videoPlayer = document.getElementById('videoPlayer');
    const videoPlayerContainer = document.getElementById('videoPlayerContainer');

    let searchTimeout;

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
        formatList.innerHTML = '<div class="loader" style="display:block"></div>';

        try {
            videoPlayer.pause();
            videoPlayer.src = '';
            videoPlayerContainer.style.display = 'none';

            const response = await fetch(`/details?url=${encodeURIComponent(url)}`);
            const details = await response.json();

            formatList.innerHTML = details.formats
                .filter(f => f.vcodec !== 'none' || f.acodec !== 'none')
                .map(f => `
                    <div class="format-item">
                        <div>
                            <strong>${f.ext.toUpperCase()}</strong> - ${f.resolution}
                            <div style="font-size: 0.8rem; color: #aaa">${f.note}</div>
                        </div>
                        <button class="download-btn" onclick="downloadVideo('${url}', '${f.id}', '${details.info.title}.${f.ext}')">
                            Download
                        </button>
                    </div>
                `).join('');

            videoPlayer.src = `/stream?url=${encodeURIComponent(url)}&formatId=${encodeURIComponent(previewFormat.id)}`;
            videoPlayerContainer.style.display = 'block';
        } catch (error) {
            formatList.innerHTML = 'Failed to load formats.';
        }
    };

    window.downloadVideo = (url, formatId, filename) => {
        const downloadUrl = `/download?url=${encodeURIComponent(url)}&formatId=${encodeURIComponent(formatId)}&filename=${encodeURIComponent(filename)}`;
        window.location.href = downloadUrl;
    };

    window.onclick = (event) => {
        if (event.target === modal) {
            modal.style.display = 'none';
            videoPlayer.pause();
            videoPlayer.src = '';
        }
    };
});
