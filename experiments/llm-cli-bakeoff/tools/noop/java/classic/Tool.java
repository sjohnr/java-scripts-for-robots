public class Tool {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--help")) {
            System.out.println("usage: noop");
            System.out.println("prints \"ok\" and exits 0");
            return;
        }
        System.out.println("ok");
    }
}
