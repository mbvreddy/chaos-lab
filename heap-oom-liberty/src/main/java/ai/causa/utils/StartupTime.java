package ai.causa.utils;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class StartupTime {
    private final long startMillis = System.currentTimeMillis();

    public long startMillis() {
        return startMillis;
    }

    public long uptimeMillis() {
        return System.currentTimeMillis() - startMillis;
    }
}
