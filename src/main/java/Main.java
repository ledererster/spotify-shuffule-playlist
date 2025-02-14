import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.SpotifyHttpManager;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.PlaylistSimplified;
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack;
import se.michaelthelin.spotify.model_objects.specification.SavedTrack;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.requests.data.library.GetUsersSavedTracksRequest;
import se.michaelthelin.spotify.requests.data.tracks.GetTrackRequest;

public class Main {

  private static final Dotenv dotenv = Dotenv.load();
  private static final URI redirectUri = SpotifyHttpManager.makeUri(
      "http://localhost:8080/callback");
  private static final String tokenFile = "spotify_tokens.json"; // File to store tokens
  private static SpotifyApi spotifyApi;
  private static HttpServer server;
  private static final String SHUFFLE_ID = dotenv.get("SHUFFLE_PLAYLIST");
  private static final int MAX_TRACKS_PER_REQUEST = 100;

  private static final CountDownLatch latch = new CountDownLatch(1);

  private static final Gson gson = new Gson();

  public static void main(String[] args) throws IOException, InterruptedException {
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

    addPlaylistToShuffleList(listPlaylists());
    deDupeAndShuffleThePlaylist();

    updatePlaylistImage();

  }

  private static void performOAuthFlow() throws IOException, InterruptedException {
    System.out.println("Starting authorization process...");

    // Step 1: Generate Authorization URI
    String authorizationUri = spotifyApi.authorizationCodeUri().scope(
            "playlist-read-private user-library-read playlist-modify-private ugc-image-upload playlist-read-collaborative") // Add required scopes
        .build().execute().toString();

    System.out.println("Open the following URI in your browser:");
    System.out.println(authorizationUri);

    // Step 2: Set up a local HTTP server to capture the authorization code
    server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext("/callback", new CallbackHandler());
    server.start();

    // Wait for the callback that retrieves tokens
    latch.await(); // Wait here until the callback signals token retrieval

    System.out.println("Token retrieved. Proceeding with further logic...");
    server.stop(0); // Shut down the server after use
  }

  private static class CallbackHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String query = exchange.getRequestURI().getQuery();
      String[] params = query.split("&");

      String code = null;
      for (String param : params) {
        if (param.startsWith("code=")) {
          code = param.split("=")[1];
          break;
        }
      }

      if (code != null) {
        try {
          // Step 3: Exchange authorization code for access and refresh tokens
          AuthorizationCodeCredentials credentials = spotifyApi.authorizationCode(code).build()
              .execute();
          spotifyApi.setAccessToken(credentials.getAccessToken());
          spotifyApi.setRefreshToken(credentials.getRefreshToken());
          saveTokensToDisk(credentials.getAccessToken(), credentials.getRefreshToken());

          String response = "Authorization successful! You can close this window.";
          exchange.sendResponseHeaders(200, response.length());
          OutputStream os = exchange.getResponseBody();
          os.write(response.getBytes());
          os.close();

          System.out.println("Authorization completed, tokens saved to disk.");
        } catch (Exception e) {
          e.printStackTrace();
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

  private static boolean loadTokensFromDisk() {
    try {
      if (Files.exists(Paths.get(tokenFile))) {
        BufferedReader reader = new BufferedReader(new FileReader(tokenFile));
        String accessToken = reader.readLine();
        String refreshToken = reader.readLine();
        reader.close();

        spotifyApi.setAccessToken(accessToken);
        spotifyApi.setRefreshToken(refreshToken);
        return true;
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return false;
  }

  private static void saveTokensToDisk(String accessToken, String refreshToken) {
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(tokenFile))) {
      writer.write(accessToken + "\n");
      writer.write(refreshToken + "\n");
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static void refreshAccessToken() {
    try {
      AuthorizationCodeCredentials credentials = spotifyApi.authorizationCodeRefresh().build()
          .execute();
      spotifyApi.setAccessToken(credentials.getAccessToken());
      saveTokensToDisk(credentials.getAccessToken(), spotifyApi.getRefreshToken());
      System.out.println("Access token refreshed.");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static boolean isAccessTokenValid() {
    try {
      spotifyApi.getCurrentUsersProfile().build().execute(); // Example API call
      return true; // Token is valid if call succeeds
    } catch (Exception e) {
      return false; // Token is invalid
    }
  }

  public static List<Playlist> listPlaylists() {
    try {
      List<Playlist> playlistData = JsonFileDb.load();
      Paging<PlaylistSimplified> playlistsPaging = spotifyApi.getListOfCurrentUsersPlaylists() // Fetch playlists
          .build().execute();

      Scanner scanner = new Scanner(System.in);

      for (PlaylistSimplified playlistSimplified : playlistsPaging.getItems()) {
        Playlist playlist = new Playlist();
        playlist.setName(playlistSimplified.getName());
        playlist.setId(playlistSimplified.getId());

        if (playlistData.contains(playlist) || playlist.getId().equals(SHUFFLE_ID)) {
          continue;
        }

        // Prompt the user for "keep" input
        System.out.printf("Playlist: %s (%d tracks)%n", playlist.getName(),
            playlistSimplified.getTracks().getTotal());
        // Prompt the user for "add to shuffle" input
        System.out.print("Add this playlist to shuffle? (y/n): ");
        String shuffleResponse = scanner.nextLine().trim().toLowerCase();
        playlist.setIncludeInShuffle(shuffleResponse.equals("y"));

        // Add to playlist data
        playlistData.add(playlist);

        System.out.println("-----------------------------"); // Just for readability
      }

      // Save updated playlist data to file
      JsonFileDb.save(playlistData);
      System.out.println("Playlists have been saved!");
      return JsonFileDb.load();

    } catch (Exception e) {
      e.printStackTrace();
    }
    return new ArrayList<>();
  }

  public static List<String> getLikedSongs() throws IOException, SpotifyWebApiException {
    List<String> likedSongsUris = new ArrayList<>();

    // Fetch liked tracks in a paginated manner
    int limit = 50; // Maximum allowed limit per request
    int offset = 0; // Start at the beginning
    boolean moreTracksAvailable = true;

    while (moreTracksAvailable) {
      GetUsersSavedTracksRequest request = spotifyApi.getUsersSavedTracks()
          .limit(limit)   // Number of tracks to retrieve per request (max 50)
          .offset(offset) // Start offset for fetching tracks
          .build();

      try {
        // Execute the request and fetch the result
        Paging<SavedTrack> savedTracks = request.execute();

        // Add track URIs to the list
        for (SavedTrack savedTrack : savedTracks.getItems()) {
          likedSongsUris.add(savedTrack.getTrack().getUri());
        }

        // Check if there are more tracks to fetch
        offset += limit;
        if (savedTracks.getItems().length == 0) {
          moreTracksAvailable = false; // No more tracks
        }
      } catch (Exception e) {
        System.err.println("Failed to fetch liked songs: " + e.getMessage());
        break;
      }
    }

    return likedSongsUris;
  }


  public static void addPlaylistToShuffleList(List<Playlist> playlistsToAdd) {
    try {
      // Step 1: Get existing songs in the "SHUFFLE ID" playlist
      List<String> shufflePlaylistTrackUris = getPlaylistTrackUris(SHUFFLE_ID);
      System.out.println("Shuffle songs : " + shufflePlaylistTrackUris.size());

      // Step 2: Load tracks (URIs) from playlists to be added
      for (Playlist playlist : playlistsToAdd) {
        if (!playlist.isIncludeInShuffle()) {
          continue;
        }
        // Get all songs from the current playlist
        List<String> currentPlaylistTracks = getPlaylistTrackUris(playlist.getId());

        // Remove tracks already in the shuffle playlist
        List<String> tracksToAdd = filterOutDuplicates(currentPlaylistTracks,
            shufflePlaylistTrackUris);

        // Step 3: Add remaining tracks to the shuffle playlist
        if (!tracksToAdd.isEmpty()) {
          shufflePlaylistTrackUris.addAll(tracksToAdd);
          addTracksInChunks(tracksToAdd);
          System.out.println(
              "Added " + tracksToAdd.size() + " tracks from playlist: " + playlist.getName());
        }
      }

      List<String> likeSongs = getLikedSongs();

      List<String> likedSongsToAdd = filterOutDuplicates(likeSongs, shufflePlaylistTrackUris);
      if (!likedSongsToAdd.isEmpty()) {
        shufflePlaylistTrackUris.addAll(likedSongsToAdd);
        addTracksInChunks(likedSongsToAdd);
        System.out.println("Added " + likedSongsToAdd.size() + " tracks from liked songs.");

      }
//          likedSongsToAdd.forEach(Main::getTrackInfo);

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void addTracksInChunks(List<String> tracksToAdd) throws Exception {
    int totalTracks = tracksToAdd.size();
    int batches = (int) Math.ceil((double) totalTracks / MAX_TRACKS_PER_REQUEST);
    System.out.println("Adding " + totalTracks + " tracks in " + batches + " batch(es)...");

    for (int i = 0; i < totalTracks; i += MAX_TRACKS_PER_REQUEST) {
      // Sublist for the current batch
      List<String> batch = tracksToAdd.subList(i,
          Math.min(i + MAX_TRACKS_PER_REQUEST, totalTracks));

      // Convert the batch to JsonArray and send the request
      spotifyApi.addItemsToPlaylist(SHUFFLE_ID, gson.toJsonTree(batch).getAsJsonArray()).build()
          .execute();

      System.out.println("Added batch of " + batch.size() + " tracks.");
    }
    System.out.println("All tracks added successfully.");
  }


  public static void getTrackInfo(String trackId) {
    try {

      if (trackId.startsWith("spotify:track:")) {
        trackId = trackId.substring(
            "spotify:track:".length()); // Extract everything after "spotify:track:"
      } else {
        throw new IllegalArgumentException("Invalid Spotify track URI: " + trackId);
      }

      // Build the request for the given track ID
      GetTrackRequest getTrackRequest = spotifyApi.getTrack(trackId).build();

      // Execute the request and get the track details
      Track track = getTrackRequest.execute();

      System.out.println(track.getName() + " - " + track.getArtists()[0].getName());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }


  // Helper to get track URIs from a playlist by ID
  private static List<String> getPlaylistTrackUris(String playlistId) throws Exception {
    List<String> trackUris = new ArrayList<>();
    int limit = 100; // Spotify API limit per request
    int offset = 0;
    Paging<PlaylistTrack> playlistTracks;

    // Fetch tracks in increments of 'limit'
    do {
      playlistTracks = spotifyApi.getPlaylistsItems(playlistId).offset(offset).limit(limit).build()
          .execute();
      for (PlaylistTrack track : playlistTracks.getItems()) {
        trackUris.add(track.getTrack().getUri());
      }
      offset += limit;
    } while (playlistTracks.getNext() != null); // Check if more pages available

    return trackUris;
  }

  // Helper to filter out duplicates
  private static List<String> filterOutDuplicates(List<String> sourceTracks,
      List<String> existingTracks) {
    Set<String> existingTrackSet = new HashSet<>(existingTracks); // Use a Set for faster lookup
    List<String> filteredTracks = new ArrayList<>();

    for (String trackUri : sourceTracks) {
      if (!existingTrackSet.contains(trackUri)) {
        filteredTracks.add(trackUri);
      }
    }
    return filteredTracks;
  }

  private static void deDupeAndShuffleThePlaylist() {
    try {
      // Step 1: Get existing songs in the "SHUFFLE ID" playlist
      List<String> shufflePlaylistTrackUris = getPlaylistTrackUris(SHUFFLE_ID);
      System.out.println("Shuffle songs : " + shufflePlaylistTrackUris.size());

      //dedupe
      Set<String> uniqueSongs = new HashSet<>(shufflePlaylistTrackUris);

      System.out.println("Unique Songs :  " + uniqueSongs.size());

      // Shuffle the unique songs
      List<String> shuffledSongs = new ArrayList<>(uniqueSongs);
      Collections.shuffle(shuffledSongs);

      spotifyApi.replacePlaylistsItems(SHUFFLE_ID, new JsonArray()).build().execute();

      shufflePlaylistTrackUris = getPlaylistTrackUris(SHUFFLE_ID);
      System.out.println("Shuffle songs : " + shufflePlaylistTrackUris.size());

      addTracksInChunks(shuffledSongs);

    } catch (Exception e) {
      e.printStackTrace();
    }

  }

  private static void updatePlaylistImage() {
    try {
      Path file = Paths.get(
          Main.class.getClassLoader().getResource("playlist_compressed.jpg").toURI());
      byte[] fileBytes = Files.readAllBytes(file);

      String base64String = Base64.getEncoder().encodeToString(fileBytes);

      spotifyApi.uploadCustomPlaylistCoverImage(SHUFFLE_ID).image_data(base64String).build()
          .execute();

    } catch (Exception e) {
      e.printStackTrace();
    }


  }

  private static void getPlaylist(String id) {

    try {
      se.michaelthelin.spotify.model_objects.specification.Playlist playlist = spotifyApi.getPlaylist(
          id).build().execute();
      System.out.println(playlist.getName());
      List<String> songs = getPlaylistTrackUris(id);
      songs.forEach(Main::getTrackInfo);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }


}