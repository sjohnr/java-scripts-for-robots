public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello " + String.join("|", args)
                + " (app.version=" + System.getProperty("app.version", "1") + ")");
    }
}
