package com.jled.playlistshuffle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.SpotifyHttpManager;
import se.michaelthelin.spotify.enums.ModelObjectType;
import se.michaelthelin.spotify.exceptions.detailed.TooManyRequestsException;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.model_objects.specification.AlbumSimplified;
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.PagingCursorbased;
import se.michaelthelin.spotify.model_objects.specification.PlaylistSimplified;
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack;
import se.michaelthelin.spotify.model_objects.specification.SavedTrack;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.model_objects.specification.TrackSimplified;
import se.michaelthelin.spotify.requests.IRequest;

public class Main {

  private static final Logger LOG = LoggerFactory.getLogger(Main.class);
  private static final Dotenv dotenv = Dotenv.load();
  private static final URI redirectUri = SpotifyHttpManager.makeUri(
      "http://127.0.0.1:8080/callback");
  private static final String TOKEN_FILE = "spotify_tokens"; // File to store tokens
  private static SpotifyApi spotifyApi;
  private static HttpServer server;
  private static final String SHUFFLE_ID = dotenv.get("SHUFFLE_PLAYLIST");
  private static final int MAX_TRACKS_PER_REQUEST = 100;

  private static final CountDownLatch latch = new CountDownLatch(1);

  private static final Set<String> shufflePlaylistTrackUris = new HashSet<>();

  private static final String SCOPES = String.join(" ", "playlist-read-private",
      "user-library-read", "playlist-modify-private", "ugc-image-upload",
      "playlist-read-collaborative", "user-follow-read");

  public static void main(String[] args) throws Exception {
    spotifyApi = new SpotifyApi.Builder().setClientId(dotenv.get("SPOTIFY_CLIENT"))
        .setClientSecret(dotenv.get("SPOTIFY_SECRET")).setRedirectUri(redirectUri).build();

    // Load tokens from disk if available
    if (!loadTokensFromDisk()) {
      // If tokens are not available, perform the authorization flow
      performOAuthFlow();
    } else if (!isAccessTokenValid()) {
      // If access token is expired, refresh it
      refreshAccessToken();
    }

    LOG.info("Loading initial playlist state");
    shufflePlaylistTrackUris.addAll(getPlaylistTrackUris(SHUFFLE_ID));
    LOG.info("Shuffle songs : {}", shufflePlaylistTrackUris.size());

    addPlaylistsToShuffleList();
    addFollowedArtistsToShuffleList();
    shuffleThePlaylist();
    updatePlaylistImage();

    LOG.info("Shuffle songs : {}", shufflePlaylistTrackUris.size());
    LOG.info("getting playlist again to verify count..");
    shufflePlaylistTrackUris.clear();
    shufflePlaylistTrackUris.addAll(getPlaylistTrackUris(SHUFFLE_ID));
    LOG.info("Shuffle songs : {}", shufflePlaylistTrackUris.size());

  }

  private static void performOAuthFlow() throws Exception {
    LOG.info("Starting authorization process...");

    // Step 1: Generate Authorization URI
    String authorizationUri = executeWithRetry(
        spotifyApi.authorizationCodeUri().scope(SCOPES) // Add required scopes
            .build()).toString();

    LOG.info("Open the following URI in your browser:");
    LOG.info(authorizationUri);

    // Step 2: Set up a local HTTP server to capture the authorization code
    server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext("/callback", new CallbackHandler());
    server.start();

    // Wait for the callback that retrieves tokens
    latch.await(); // Wait here until the callback signals token retrieval

    LOG.info("Token retrieved. Proceeding with further logic...");
    server.stop(0); // Shut down the server after use
  }

  private static class CallbackHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String query = exchange.getRequestURI().getQuery();

      String code = Arrays.stream(query.split("&")).filter(param -> param.startsWith("code="))
          .map(param -> param.split("=")[1]).findFirst().orElse(null);

      if (code != null) {
        try {
          // Step 3: Exchange authorization code for access and refresh tokens
          AuthorizationCodeCredentials credentials = executeWithRetry(
              spotifyApi.authorizationCode(code).build());
          spotifyApi.setAccessToken(credentials.getAccessToken());
          spotifyApi.setRefreshToken(credentials.getRefreshToken());
          saveTokensToDisk(credentials.getAccessToken(), credentials.getRefreshToken());

          String response = "Authorization successful! You can close this window.";
          exchange.sendResponseHeaders(200, response.length());
          OutputStream os = exchange.getResponseBody();
          os.write(response.getBytes());
          os.close();

          LOG.info("Authorization completed, tokens saved to disk.");
        } catch (Exception e) {
          throw new IOException(e.getMessage(), e);
        } finally {
          latch.countDown();
          server.stop(0); // Stop the local HTTP server
        }
      } else {
        String response = "Authorization failed. Missing 'code' parameter.";
        exchange.sendResponseHeaders(400, response.length());
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
      }
    }
  }

  private static boolean loadTokensFromDisk() throws IOException {
    Path tokenPath = Paths.get(TOKEN_FILE);
    if (!Files.exists(tokenPath)) {
      return false;
    }

    List<String> tokens = Files.readAllLines(tokenPath);
    if (tokens.size() < 2) {
      return false;
    }

    spotifyApi.setAccessToken(tokens.get(0));
    spotifyApi.setRefreshToken(tokens.get(1));
    return true;
  }

  private static void saveTokensToDisk(String accessToken, String refreshToken) throws IOException {
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(TOKEN_FILE))) {
      writer.write(accessToken + "\n");
      writer.write(refreshToken + "\n");
    }
  }

  private static void refreshAccessToken() throws Exception {
    AuthorizationCodeCredentials credentials = executeWithRetry(
        spotifyApi.authorizationCodeRefresh().build());
    spotifyApi.setAccessToken(credentials.getAccessToken());
    saveTokensToDisk(credentials.getAccessToken(), spotifyApi.getRefreshToken());
    LOG.info("Access token refreshed.");
  }

  private static boolean isAccessTokenValid() {
    try {
      executeWithRetry(spotifyApi.getCurrentUsersProfile().build()); // Example API call
      return true; // Token is valid if call succeeds
    } catch (Exception e) {
      return false; // Token is invalid
    }
  }

  public static List<Playlist> listPlaylists() throws Exception {
    List<Playlist> playlistData = JsonFileDb.loadPlaylists();
    Paging<PlaylistSimplified> playlistsPaging = executeWithRetry(
        spotifyApi.getListOfCurrentUsersPlaylists() // Fetch playlists
            .build());

    Scanner scanner = new Scanner(System.in);

    for (PlaylistSimplified playlistSimplified : playlistsPaging.getItems()) {
      Playlist playlist = new Playlist();
      playlist.setName(playlistSimplified.getName());
      playlist.setId(playlistSimplified.getId());

      if (playlistData.contains(playlist) || playlist.getId().equals(SHUFFLE_ID)) {
        continue;
      }

      // Prompt the user for "keep" input
      LOG.info("Playlist: {} ({} tracks)", playlist.getName(),
          playlistSimplified.getTracks().getTotal());
      // Prompt the user for "add to shuffle" input
      LOG.info("Add this playlist to shuffle? (y/n): ");
      String shuffleResponse = scanner.nextLine().trim().toLowerCase();
      playlist.setIncludeInShuffle(shuffleResponse.equals("y"));

      // Add to playlist data
      playlistData.add(playlist);

      LOG.info("-----------------------------"); // Just for readability
    }

    // Save updated playlist data to file
    JsonFileDb.savePlaylists(playlistData);
    LOG.info("Playlists have been saved!");
    return JsonFileDb.loadPlaylists();
  }

  public static List<String> getLikedSongs() throws Exception {
    List<String> likedSongsUris = new ArrayList<>();

    // Fetch liked tracks in a paginated manner
    int limit = 50; // Maximum allowed limit per request
    int offset = 0; // Start at the beginning
    boolean moreTracksAvailable = true;

    while (moreTracksAvailable) {
      Paging<SavedTrack> savedTracks = executeWithRetry(spotifyApi.getUsersSavedTracks()
          .limit(limit)   // Number of tracks to retrieve per request (max 50)
          .offset(offset) // Start offset for fetching tracks
          .build());

      // Add track URIs to the list
      for (SavedTrack savedTrack : savedTracks.getItems()) {
        likedSongsUris.add(savedTrack.getTrack().getUri());
      }

      // Check if there are more tracks to fetch
      offset += limit;
      if (savedTracks.getNext() == null) {
        moreTracksAvailable = false;
      }
    }

    return likedSongsUris;
  }


  public static void addPlaylistsToShuffleList() throws Exception {
    List<Playlist> playlistsToAdd = listPlaylists();
    // Step 2: Load tracks (URIs) from playlists to be added
    for (Playlist playlist : playlistsToAdd) {
      if (!playlist.isIncludeInShuffle()) {
        continue;
      }
      // Get all songs from the current playlist
      List<String> currentPlaylistTracks = getPlaylistTrackUris(playlist.getId());

      shufflePlaylistTrackUris.addAll(currentPlaylistTracks);
      LOG.info("Added {} tracks from playlist: {}", currentPlaylistTracks.size(),
          playlist.getName());
    }

    List<String> likedSongs = getLikedSongs();

    shufflePlaylistTrackUris.addAll(likedSongs);
    LOG.info("Added {} tracks from liked songs.", likedSongs.size());

  }

  public static void addFollowedArtistsToShuffleList() throws Exception {
    List<Artist> artistsToAdd = listArtists();

    for (Artist artist : artistsToAdd) {
      List<String> artistTracks = getArtistTopTracks(artist.getId());
      shufflePlaylistTrackUris.addAll(artistTracks);
      LOG.info("Added {} tracks from artist: {}", artistTracks.size(), artist.getName());
    }

  }

  private static void addTracksInChunks(List<String> tracksToAdd) throws Exception {
    int totalTracks = tracksToAdd.size();
    int batches = (int) Math.ceil((double) totalTracks / MAX_TRACKS_PER_REQUEST);
    Gson gson = new Gson();
    LOG.info("Adding {} tracks in {} batch(es)...", totalTracks, batches);

    for (int i = 0; i < totalTracks; i += MAX_TRACKS_PER_REQUEST) {
      List<String> batch = tracksToAdd.subList(i,
          Math.min(i + MAX_TRACKS_PER_REQUEST, totalTracks));

        executeWithRetry(
            spotifyApi.addItemsToPlaylist(SHUFFLE_ID, gson.toJsonTree(batch).getAsJsonArray()).build());
        LOG.info("Added batch of {} tracks.", batch.size());
    }
    LOG.info("All tracks added successfully.");
  }


  private static List<String> getPlaylistTrackUris(String playlistId) throws Exception {
    List<String> trackUris = Collections.synchronizedList(new ArrayList<>()); // Thread-safe list
    int limit = 100; // API limit per request
    int totalTracks = executeWithRetry(spotifyApi.getPlaylist(playlistId).build()).getTracks()
        .getTotal();

    List<CompletableFuture<List<String>>> futures = new ArrayList<>();

    for (int offset = 0; offset < totalTracks; offset += limit) {
      int finalOffset = offset;
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          Paging<PlaylistTrack> playlistTracks = executeWithRetry(
              spotifyApi.getPlaylistsItems(playlistId).offset(finalOffset).limit(limit).build());
          return Arrays.stream(playlistTracks.getItems()).map(track -> track.getTrack().getUri())
              .toList();
        } catch (Exception e) {
          LOG.error("Error fetching tracks: {}", e.getMessage());
          return List.of();
        }
      }));
    }

// Combine all async results
    trackUris.addAll(futures.stream().map(CompletableFuture::join).flatMap(List::stream).toList());
    return trackUris;
  }

  private static void shuffleThePlaylist() throws Exception {

    List<String> shufflePlaylistTrackUrisCopy = new ArrayList<>(shufflePlaylistTrackUris);
    Collections.shuffle(shufflePlaylistTrackUrisCopy);

    executeWithRetry(spotifyApi.replacePlaylistsItems(SHUFFLE_ID, new JsonArray()).build());

    addTracksInChunks(shufflePlaylistTrackUrisCopy);


  }

  private static void updatePlaylistImage() throws Exception {
    InputStream resourceAsStream = Main.class.getClassLoader()
        .getResourceAsStream("playlist_compressed.jpg");
    if (resourceAsStream == null) {
      throw new IllegalArgumentException("Resource not found: playlist_compressed.jpg");
    }

    byte[] fileBytes = resourceAsStream.readAllBytes();
    resourceAsStream.close();

    String base64String = Base64.getEncoder().encodeToString(fileBytes);

    executeWithRetry(
        spotifyApi.uploadCustomPlaylistCoverImage(SHUFFLE_ID).image_data(base64String).build());

  }

  private static List<Artist> listArtists() throws Exception {
    List<Artist> artistData = JsonFileDb.loadArtists();
    PagingCursorbased<se.michaelthelin.spotify.model_objects.specification.Artist> artistPaging = executeWithRetry(
        spotifyApi.getUsersFollowedArtists(ModelObjectType.ARTIST).build());

    Scanner scanner = new Scanner(System.in);

    for (se.michaelthelin.spotify.model_objects.specification.Artist followedArtist : artistPaging.getItems()) {
      Artist artist = new Artist();
      artist.setName(followedArtist.getName());
      artist.setId(followedArtist.getId());

      if (artistData.contains(artist) || artist.getId().equals(SHUFFLE_ID)) {
        continue;
      }

      // Prompt the user for "keep" input
      LOG.info("Artist: {}", artist.getName());
      // Prompt the user for "add to shuffle" input
      LOG.info("Add this artist to shuffle? (y/n): ");
      String shuffleResponse = scanner.nextLine().trim().toLowerCase();
      artist.setIncludeInShuffle(shuffleResponse.equals("y"));

      // Add to artist data
      artistData.add(artist);

      LOG.info("-----------------------------"); // Just for readability
    }

    // Save updated playlist data to file
    JsonFileDb.saveArtists(artistData);
    LOG.info("Artists have been saved!");
    return JsonFileDb.loadArtists();

  }

  private static List<String> getArtistTopTracks(String artistId) throws Exception {
    int limit = 50; // Max per request
    List<Track> allTracks = Collections.synchronizedList(new ArrayList<>()); // Thread-safe list

    // **Step 1: Fetch ALL albums**
    List<AlbumSimplified> albums = new ArrayList<>();
    Paging<AlbumSimplified> albumPaging;
    int offset = 0;

    do {
      albumPaging = executeWithRetry(
          spotifyApi.getArtistsAlbums(artistId).setQueryParameter("include_groups", "album,single")
              .limit(limit).offset(offset).build());

      albums.addAll(Arrays.asList(albumPaging.getItems()));
      offset += limit;
    } while (albumPaging.getNext() != null);

    // **Step 2: Collect ALL track IDs from albums**
    List<String> trackIds = Collections.synchronizedList(new ArrayList<>());
    List<CompletableFuture<Void>> albumFutures = new ArrayList<>();

    for (AlbumSimplified album : albums) {
      albumFutures.add(CompletableFuture.runAsync(() -> {
        try {
          Paging<TrackSimplified> trackPaging = executeWithRetry(
              spotifyApi.getAlbumsTracks(album.getId()).limit(limit).build());
          synchronized (trackIds) {
            for (TrackSimplified track : trackPaging.getItems()) {
              trackIds.add(track.getId());
            }
          }
        } catch (Exception e) {
          LOG.error("Error fetching tracks from album {}: {}", album.getName(), e.getMessage());
        }
      }));
    }

    CompletableFuture.allOf(albumFutures.toArray(new CompletableFuture[0])).join();

    // **Step 3: Fetch track details in parallel (in batches of 50)**
    List<CompletableFuture<Void>> trackFutures = new ArrayList<>();

    for (int i = 0; i < trackIds.size(); i += limit) {
      int end = Math.min(i + limit, trackIds.size());
      String[] batchIds = trackIds.subList(i, end).toArray(new String[0]);

      trackFutures.add(CompletableFuture.runAsync(() -> {
        try {
          Track[] tracks = executeWithRetry(spotifyApi.getSeveralTracks(batchIds).build());
          synchronized (allTracks) {
            allTracks.addAll(Arrays.asList(tracks));
          }
        } catch (Exception e) {
          LOG.error("Error fetching track details: {}", e.getMessage());
        }
      }));
    }

    CompletableFuture.allOf(trackFutures.toArray(new CompletableFuture[0])).join();

    // **Step 4: Sort by popularity and return top 25**
    return allTracks.stream().sorted(Comparator.comparingInt(Track::getPopularity).reversed())
        .limit(25).map(Track::getUri).toList();
  }

  private static <T> T executeWithRetry(IRequest<T> request) throws Exception {
    int maxRetries = 10;
    int attempt = 0;

    while (attempt < maxRetries) {
      try {
        return request.execute();
      } catch (TooManyRequestsException e) {
        int retryAfter = e.getRetryAfter();
        LOG.debug("Rate limited. Retrying after {} seconds...", retryAfter);
        Thread.sleep(retryAfter * 1000L);
      }
      attempt++;
    }
    throw new RuntimeException("Max retry attempts exceeded");
  }


}