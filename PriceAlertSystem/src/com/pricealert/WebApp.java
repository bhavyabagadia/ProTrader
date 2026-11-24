
package com.pricealert;

public final class WebApp {

    private WebApp() {
    }

    public static void main(String[] args) throws Exception {

        int port = 8080;

        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Usage: run.bat [port]");
                System.exit(1);
            }
        }

        new ProTraderServer().start(port);
    }
}
