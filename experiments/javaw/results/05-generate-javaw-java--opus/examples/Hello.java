public class Hello {
    public static void main(String[] args) {
        System.out.println("hello " + String.join(",", args) + " version=" + System.getProperty("app.version", "1"));
    }
}
