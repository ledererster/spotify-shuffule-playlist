import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class JsonFileDb {
    private static final String FILE_PATH = "data.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void save(List<Playlist> people) throws IOException, IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(FILE_PATH), people);
    }

    public static List<Playlist> load() throws IOException {
        File file = new File(FILE_PATH);
        if (!file.exists()) return new ArrayList<>(); // Return empty list if no file
        return mapper.readValue(file, new TypeReference<>() {});
    }
}