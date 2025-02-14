# Spotify Playlist Shuffler 🎵

This project is an application to manage a Spotify playlist, shuffle songs, de-duplicate tracks, and update playlist images using the Spotify Web API. It integrates OAuth2.0 for authentication and authorization while using Spotify's extensive API to manipulate playlists and liked songs.

---

## Features ✨

- **OAuth2.0 Integration**:
    - Supports Spotify authorization flow.
    - Handles access token generation, refreshing, and storage.

- **Playlist Management**:
    - Fetches user's playlists and allows selection of specific playlists for shuffling.
    - Adds tracks from liked songs and selected playlists to a target shuffle playlist.
    - De-duplicates and shuffles the target shuffle playlist.

- **Image Customization**:
    - Updates the cover image of the shuffle playlist using a user-provided image.

- **Local Storage**:
    - Saves access and refresh tokens securely to disk.

---

## Requirements 🛠️

### Pre-requisites
1. **Spotify Developer Account**:
    - Create your Spotify app at the [Spotify Developer Console](https://developer.spotify.com/dashboard).
    - Note down your `Client ID`, `Client Secret`, and setup a redirect URI (e.g., `http://localhost:8080/callback`).

2. **Java Environment**:
    - Requires Java 21 or newer. Ensure the appropriate SDK is installed.

3. **Environment Variables**:
    - Create a `.env` file in the root directory or use environment variable management tools like:

   ```dotenv
   SPOTIFY_CLIENT=your_spotify_client_id
   SPOTIFY_SECRET=your_spotify_client_secret
   SHUFFLE_PLAYLIST=your_target_shuffle_playlist_id
   ```

---

## Installation 📦

1. **Clone the Repository**
   ```bash
   git clone https://github.com/YourGitHubUsername/spotify-playlist-shuffler.git
   cd spotify-playlist-shuffler
   ```

2. **Setup Dependencies**
    - Define your Gradle dependencies in `build.gradle`.
    - Run:
      ```bash
      ./gradlew build
      ```

3. **Prepare the Environment**
    - Add a `.env` file with your `SPOTIFY_CLIENT`, `SPOTIFY_SECRET`, and target playlist ID (`SHUFFLE_PLAYLIST`).

4. **Run the Application**
   ```bash
   java -jar build/libs/spotify-playlist-shuffler-1.0-SNAPSHOT.jar
   ```

---

## How It Works ⚙️

### 1. **Authorization Flow**
- The application initiates Spotify's OAuth2.0 authentication.
- Run the application and open the provided URL in your web browser to authorize.
- A local HTTP server captures the callback and retrieves access tokens.

### 2. **Playlist Manipulation**
- Pulls user playlists and tracks from liked songs.
- Allows users to select playlists for shuffling.
- De-duplicates tracks and ensures unique song additions to the `SHUFFLE_PLAYLIST`.

### 3. **Shuffling & Updating**
- The application shuffles the tracks 20 times for randomness.
- Updates the existing shuffle playlist with new tracks.
- Refreshes the playlist cover image using user-provided artwork.

---

## Commands 🛠️

### Build & Run
- **Build the project**:
   ```bash
   ./gradlew build
   ```

- **Run**:
   ```bash
   java -jar build/libs/spotify-playlist-shuffler-1.0-SNAPSHOT.jar
   ```

---

## Dependencies 📚

The following libraries are used in the project:

- **Spotify Web API Java**: `9.1.1`
- **dotenv-java**: `3.1.0` (Environment configuration)
- **Gson**: Parsing and handling JSON
- **Jackson Databind**: Additional JSON utilities
- **Slf4j / Logback**: Logging support.

---

## Configuration ⚙️

### `.env File Format`
```dotenv
SPOTIFY_CLIENT=your_spotify_client_id
SPOTIFY_SECRET=your_spotify_client_secret
SHUFFLE_PLAYLIST=your_target_shuffle_playlist_id
```

> Replace values accordingly based on your Spotify app credentials and target shuffle playlist.

---

## Functionality Breakdown 📖

### Key Methods Implemented:

#### 1. **`performOAuthFlow()`**
- Handles Spotify's OAuth2.0 authorization flow.
- Spins up a local HTTP server to capture the callback.

#### 2. **`listPlaylists()`**
- Fetches and prompts users to select playlists for processing.

#### 3. **`addPlaylistToShuffleList()`**
- Adds tracks from liked songs & user-selected playlists to the shuffle playlist.

#### 4. **`deDupeAndShuffleThePlaylist()`**
- Removes duplicate tracks and shuffles the playlist for randomness.

#### 5. **`updatePlaylistImage()`**
- Updates the cover image for the target playlist programmatically.

---

## Contributing 🤝

1. Fork the repository.
2. Create a new branch for your feature or bugfix (`git checkout -b feature-name`).
3. Submit a Pull Request!

---

## License 📝

This project is licensed under the MIT License. See the LICENSE file for details.