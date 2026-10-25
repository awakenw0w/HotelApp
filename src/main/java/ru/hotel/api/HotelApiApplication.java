package ru.hotel.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Запускает сервер API гостиницы. */
@SpringBootApplication
public class HotelApiApplication {

    /** Точка входа в программу. */
    public static void main(String[] args) {
        SpringApplication.run(HotelApiApplication.class, args);
    }
}
