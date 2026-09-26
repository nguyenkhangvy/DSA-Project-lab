package vn.edu.hcmiu.sla;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SlaWebApplication {

    static {
        // The database stores times as UTC without a zone; pages convert them to Vietnam time.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(SlaWebApplication.class, args);
    }
}
