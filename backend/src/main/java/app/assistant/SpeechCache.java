package app.assistant;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

// Recently spoken texts and their audio, so repeated phrases (guide steps, standard replies)
// are only paid for once. Least recently used entries are dropped first. In memory only.
@Component
public class SpeechCache {

    static final int MAX_ENTRIES = 200;

    private final Map<String, byte[]> audioByText = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public synchronized byte[] get(String text) {
        return audioByText.get(text);
    }

    public synchronized void put(String text, byte[] audio) {
        audioByText.put(text, audio);
    }
}
