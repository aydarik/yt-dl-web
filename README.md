# YT-DL Web

A clean, self-hosted web app for searching and downloading YouTube videos — no ads, no tracking, no sign-in required.

---

## What it does

- **Search** for any video by typing a title, artist, or keyword
- **Paste a YouTube URL** directly to skip search and go straight to download
- **Choose your quality** — pick from 1080p, 720p, 480p, 360p video, or audio-only (MP3 / M4A)
- **Watch in browser** — cached videos play instantly without re-downloading
- **Save to your device** — download the file once it's ready
- **Removes sponsor segments** — sponsored segments and self-promotions are automatically cut out

---

## How to use it

### Searching for a video

1. Type a search term into the search bar (e.g. `lofi hip hop` or `Rick Astley Never Gonna`)
2. Press **Enter** or click the **↵ button**
3. Click any result card to open the download panel

### Downloading directly from a URL

1. Copy a YouTube video URL (e.g. `https://www.youtube.com/watch?v=...`)
2. Paste it into the search bar — the download panel opens immediately

### Choosing quality and downloading

Once a video is open:

1. Select your preferred quality from the **Choose quality** panel:

   | Option | Best for |
   |---|---|
   | **1080p HD Video** | Full HD, largest file size |
   | **720p HD Video** | Good balance of quality and size *(default)* |
   | **480p Video** | Smaller file, still watchable |
   | **360p Video** | Lowest quality, fastest download |
   | **Audio Only (MP3)** | Music, podcasts — no video |
   | **Audio Only (M4A)** | Higher quality audio |

2. The download starts automatically once you select a format
3. A progress bar shows download status
4. When complete, click **Save MP4** (or **Save MP3**) to save the file to your device
5. Click **▶ Play** to watch directly in the browser

### Cancelling a download

Click **✕ Cancel** during a download to stop it. You can then pick a different quality and try again using the **↺ Re-download** button.

---

## Running it

The easiest way is with Docker:

```bash
docker compose up -d
```

Then open **http://localhost:8080** in your browser.

To stop it:

```bash
docker compose down
```

### Optional: Cookie support

Some videos require you to be signed in to download (age-restricted content, members-only, etc.).  
You can provide a browser cookie file by setting the `COOKIES` environment variable in `docker-compose.yml`:

```yaml
services:
  yt-dl-web:
    build: .
    ports:
      - "8080:8080"
    restart: unless-stopped
    environment:
      COOKIES: /cookies/cookies.txt
    volumes:
      - ./cookies:/cookies
```

Export your YouTube cookies from a browser and place the file at `./cookies/cookies.txt`.

### Optional: Authentication

By default, the app is open and requires no credentials. To protect the app with HTTP Basic Authentication, set the `APP_USERS` environment variable with a comma-separated list of `username:password` pairs:

```yaml
    environment:
      APP_USERS: "alice:secret,bob:pass123"
```

---

## Limits

- Maximum file size: **1 GB** per download
- Downloads are cached on the server — closing the browser tab does not delete them
- Cancelling a download removes the cached file

---

## Disclaimer

This project is not affiliated with, endorsed by, or in any way connected to YouTube or Google LLC. Only download content you have the right to download. Respect copyright law and the [YouTube Terms of Service](https://www.youtube.com/t/terms).
