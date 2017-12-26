package com.redhat.btison.enmasse;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Callable;

public class TestUtils {

    public static boolean resolvable(Endpoint endpoint) {
        for (int i = 0; i < 10; i++) {
            try {
                InetAddress[] addresses = Inet4Address.getAllByName(endpoint.getHost());
                Thread.sleep(1000);
                return addresses.length > 0;
            } catch (Exception e) {
                Thread.interrupted();
            }
        }
        return false;
    }

    public static <T> T doRequestNTimes(int retry, Callable<T> fn) throws Exception {
        try {
            return fn.call();
        } catch (Exception ex) {
            if (ex.getCause() instanceof UnknownHostException && retry > 0) {
                try {
                    return doRequestNTimes(retry - 1, fn);
                } catch (Exception ex2) {
                    throw ex2;
                }
            } else {
                if (ex.getCause() != null) {
                    ex.getCause().printStackTrace();
                } else {
                    ex.printStackTrace();
                }
                throw ex;
            }
        }
    }
}
