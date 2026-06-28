package me.arrow.managers.logs;

import lombok.Getter;

import java.text.SimpleDateFormat;
import java.util.Date;

@Getter
public class PlayerLog {

    String player;
    String uuid;
    String check;
    String information;
    String timeStamp;

    public PlayerLog(String player, String uuid, String check, String information) {
        this.player = player;
        this.uuid = uuid;
        this.check = check;
        this.information = information == null ? "" : (information.length() > 500 ? information.substring(0, 500) : information);
        this.timeStamp = new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss").format(new Date());
    }

    public PlayerLog(String player, String uuid, String check, String information, String timeStamp) {
        this.player = player;
        this.uuid = uuid;
        this.check = check;
        this.information = information;
        this.timeStamp = timeStamp;
    }

    @Override
    public String toString() {
        return this.player + "," + this.uuid + "," + this.check + "," + this.information.replace(",", "") + "," + this.timeStamp;
    }
}
