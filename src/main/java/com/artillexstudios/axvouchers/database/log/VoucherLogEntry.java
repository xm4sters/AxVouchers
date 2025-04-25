package com.artillexstudios.axvouchers.database.log;

import java.sql.Timestamp;
import java.util.UUID;

public record VoucherLogEntry(int id, String type, Timestamp time, UUID uuid, String duped, String placeholders) {

}
