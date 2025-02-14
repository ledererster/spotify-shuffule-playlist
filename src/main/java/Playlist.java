import java.util.Objects;

public class Playlist {

    private String name;
    private String id;
    private boolean includeInShuffle;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean isIncludeInShuffle() {
        return includeInShuffle;
    }

    public void setIncludeInShuffle(boolean includeInShuffle) {
        this.includeInShuffle = includeInShuffle;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Playlist playlist = (Playlist) o;
        return Objects.equals(id, playlist.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
