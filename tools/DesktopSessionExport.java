import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.prefs.Preferences;

final class DesktopSessionExport {
    private static final String APPLICATION_ID = "dev.obiente.nextcloudnative";

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("This helper does not accept arguments.");
        }

        var preferences = Preferences.userRoot().node("dev/obiente/nextcloudnative");
        var server = required(preferences.get("server", null), "desktop server");
        var login = required(preferences.get("login", null), "desktop login");
        var password = lookupPassword(server, login);
        System.out.print(
            "{\"serverUrl\":" + quote(server)
                + ",\"loginName\":" + quote(login)
                + ",\"appPassword\":" + quote(password)
                + "}"
        );
    }

    private static String lookupPassword(String server, String login)
        throws IOException, InterruptedException {
        var process = new ProcessBuilder(
            "secret-tool",
            "lookup",
            "application",
            APPLICATION_ID,
            "server",
            server,
            "login",
            login
        ).start();
        var password = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
            .stripTrailing();
        var error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
            .strip();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(
                error.isBlank() ? "Could not read the desktop keyring." : error
            );
        }
        return required(password, "desktop app password");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The " + label + " is unavailable.");
        }
        return value;
    }

    private static String quote(String value) {
        var output = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        return output.append('"').toString();
    }
}
