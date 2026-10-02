public class Hello {
    public static void main(String[] args) {
        String version = System.getProperty("app.version", "1");
        System.out.println("Hello " + String.join(" ", args) + " (app.version=" + version + ")");
    }
}
