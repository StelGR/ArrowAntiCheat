package me.arrow.files.commentedfiles;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


import java.util.*;

public class CommentedConfigurationSection implements ConfigurationSection {

    protected ConfigurationSection config;

    public CommentedConfigurationSection(ConfigurationSection configuration) {
        this.config = configuration;
    }

    /**
     * Gets a defaulted boolean value. These accept values of either "default", true, or false
     *
     * @param path The value key
     * @return null for "default", otherwise true or false
     */
    public Boolean getDefaultedBoolean(String path) {
        if (this.isBoolean(path)) {
            return this.getBoolean(path);
        } else if (this.isString(path)) {
            String stringValue = this.getString(path);
            if (stringValue != null && stringValue.equalsIgnoreCase("default"))
                return null;
        }

        return null;
    }

    /**
     * Gets a defaulted boolean value. These accept values of either "default", true, or false
     *
     * @param path The value key
     * @param def  The value to return if the key is not found
     * @return null for "default", otherwise true or false
     */
    public Boolean getDefaultedBoolean(String path, Boolean def) {
        Object value = this.get(path);
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String stringValue) {
            if (stringValue.equalsIgnoreCase("default"))
                return null;
        }

        if (value == null)
            return def;

        return null;
    }

    @Override
    public @NotNull Set<String> getKeys(boolean b) {
        return this.config.getKeys(b);
    }

    @Override
    public @NotNull Map<String, Object> getValues(boolean b) {
        return this.config.getValues(b);
    }

    @Override
    public boolean contains(@NotNull String s) {
        return this.config.contains(s);
    }

    /**
     */
    @Override
    public boolean contains(@NotNull String s, boolean b) {
        return this.config.contains(s, b);
    }


    @Override
    public boolean isSet(@NotNull String s) {
        return this.config.isSet(s);
    }

    @Override
    public String getCurrentPath() {
        return this.config.getCurrentPath();
    }

    @Override
    public @NotNull String getName() {
        return this.config.getName();
    }

    @Override
    public Configuration getRoot() {
        return this.config.getRoot();
    }

    @Override
    public ConfigurationSection getParent() {
        return this.config.getParent();
    }

    @Override
    public Object get(@NotNull String s) {
        return this.config.get(s);
    }

    @Override
    public Object get(@NotNull String s, Object o) {
        return this.config.get(s, o);
    }

    @Override
    public void set(@NotNull String s, Object o) {
        this.config.set(s, o);
    }

    @Override
    public @NotNull CommentedConfigurationSection createSection(@NotNull String s) {
        return new CommentedConfigurationSection(this.config.createSection(s));
    }

    @Override
    public @NotNull CommentedConfigurationSection createSection(@NotNull String s, @NotNull Map<?, ?> map) {
        return new CommentedConfigurationSection(this.config.createSection(s, map));
    }

    @Override
    public String getString(@NotNull String s) {
        return this.config.getString(s);
    }

    @Override
    public String getString(@NotNull String s, String s1) {
        return this.config.getString(s, s1);
    }

    @Override
    public boolean isString(@NotNull String s) {
        return this.config.isString(s);
    }

    @Override
    public int getInt(@NotNull String s) {
        return this.config.getInt(s);
    }

    @Override
    public int getInt(@NotNull String s, int i) {
        return this.config.getInt(s, i);
    }

    @Override
    public boolean isInt(@NotNull String s) {
        return this.config.isInt(s);
    }

    @Override
    public boolean getBoolean(@NotNull String s) {
        return this.config.getBoolean(s);
    }

    @Override
    public boolean getBoolean(@NotNull String s, boolean b) {
        return this.config.getBoolean(s, b);
    }

    @Override
    public boolean isBoolean(@NotNull String s) {
        return this.config.isBoolean(s);
    }

    @Override
    public double getDouble(@NotNull String s) {
        return this.config.getDouble(s);
    }

    @Override
    public double getDouble(@NotNull String s, double v) {
        return this.config.getDouble(s, v);
    }

    @Override
    public boolean isDouble(@NotNull String s) {
        return this.config.isDouble(s);
    }

    @Override
    public long getLong(@NotNull String s) {
        return this.config.getLong(s);
    }

    @Override
    public long getLong(@NotNull String s, long l) {
        return this.config.getLong(s, l);
    }

    @Override
    public boolean isLong(@NotNull String s) {
        return this.config.isLong(s);
    }

    @Override
    public List<?> getList(@NotNull String s) {
        return this.config.getList(s);
    }

    @Override
    public List<?> getList(@NotNull String s, List<?> list) {
        return this.config.getList(s, list);
    }

    @Override
    public boolean isList(@NotNull String s) {
        return this.config.isList(s);
    }

    @Override
    public @NotNull List<String> getStringList(@NotNull String s) {
        return this.config.getStringList(s);
    }

    @Override
    public @NotNull List<Integer> getIntegerList(@NotNull String s) {
        return this.config.getIntegerList(s);
    }

    @Override
    public @NotNull List<Boolean> getBooleanList(@NotNull String s) {
        return this.config.getBooleanList(s);
    }

    @Override
    public @NotNull List<Double> getDoubleList(@NotNull String s) {
        return this.config.getDoubleList(s);
    }

    @Override
    public @NotNull List<Float> getFloatList(@NotNull String s) {
        return this.config.getFloatList(s);
    }

    @Override
    public @NotNull List<Long> getLongList(@NotNull String s) {
        return this.config.getLongList(s);
    }

    @Override
    public @NotNull List<Byte> getByteList(@NotNull String s) {
        return this.config.getByteList(s);
    }

    @Override
    public @NotNull List<Character> getCharacterList(@NotNull String s) {
        return this.config.getCharacterList(s);
    }

    @Override
    public @NotNull List<Short> getShortList(@NotNull String s) {
        return this.config.getShortList(s);
    }

    @Override
    public @NotNull List<Map<?, ?>> getMapList(@NotNull String s) {
        return this.config.getMapList(s);
    }

    /**
     */
    @Override
    public @Nullable <T> T getObject(@NotNull String s, @NotNull Class<T> aClass) {
        return this.config.getObject(s, aClass);
    }

    /**
     */
    @Override
    public @Nullable <T> T getObject(@NotNull String s, @NotNull Class<T> aClass, @Nullable T t) {
        return this.config.getObject(s, aClass, t);
    }

    /**
     */
    @Override
    public @Nullable <T extends ConfigurationSerializable> T getSerializable(@NotNull String s, @NotNull Class<T> aClass) {
        return this.config.getSerializable(s, aClass);
    }

    @Override
    public @Nullable <T extends ConfigurationSerializable> T getSerializable(@NotNull String s, @NotNull Class<T> aClass, @Nullable T t) {
        return this.config.getSerializable(s, aClass, t);
    }


    @Override
    public Vector getVector(@NotNull String s) {
        return this.config.getVector(s);
    }

    @Override
    public Vector getVector(@NotNull String s, Vector vector) {
        return this.config.getVector(s, vector);
    }

    @Override
    public boolean isVector(@NotNull String s) {
        return this.config.isVector(s);
    }

    @Override
    public OfflinePlayer getOfflinePlayer(@NotNull String s) {
        return this.config.getOfflinePlayer(s);
    }

    @Override
    public OfflinePlayer getOfflinePlayer(@NotNull String s, OfflinePlayer offlinePlayer) {
        return this.config.getOfflinePlayer(s, offlinePlayer);
    }

    @Override
    public boolean isOfflinePlayer(@NotNull String s) {
        return this.config.isOfflinePlayer(s);
    }

    @Override
    public ItemStack getItemStack(@NotNull String s) {
        return this.config.getItemStack(s);
    }

    @Override
    public ItemStack getItemStack(@NotNull String s, ItemStack itemStack) {
        return this.config.getItemStack(s, itemStack);
    }

    @Override
    public boolean isItemStack(@NotNull String s) {
        return this.config.isItemStack(s);
    }

    @Override
    public Color getColor(@NotNull String s) {
        return this.config.getColor(s);
    }

    @Override
    public Color getColor(@NotNull String s, Color color) {
        return this.config.getColor(s, color);
    }

    @Override
    public boolean isColor(@NotNull String s) {
        return this.config.isColor(s);
    }

    @Override
    public @Nullable Location getLocation(@NotNull String s) {
        return this.config.getLocation(s);
    }

    @Override
    public @Nullable Location getLocation(@NotNull String s, @Nullable Location location) {
        return this.config.getLocation(s, location);
    }

    @Override
    public boolean isLocation(@NotNull String s) {
        return this.config.isLocation(s);
    }

    @Override
    public CommentedConfigurationSection getConfigurationSection(@NotNull String s) {
        ConfigurationSection section = this.config.getConfigurationSection(s);
        if (section == null)
            return this.createSection(s);

        return new CommentedConfigurationSection(section);
    }

    @Override
    public boolean isConfigurationSection(@NotNull String s) {
        return this.config.isConfigurationSection(s);
    }

    @Override
    public CommentedConfigurationSection getDefaultSection() {
        return new CommentedConfigurationSection(this.config.getDefaultSection());
    }

    @Override
    public void addDefault(@NotNull String s, Object o) {
        this.config.addDefault(s, o);
    }


    @Override
    public @NotNull List<String> getComments(@NotNull String s) {
        return this.config.getComments(s);
    }


    @Override
    public @NotNull List<String> getInlineComments(@NotNull String s) {
        return this.config.getInlineComments(s);
    }


    @Override
    public void setComments(@NotNull String s, @Nullable List<String> list) {
        this.config.setComments(s, list);
    }


    @Override
    public void setInlineComments(@NotNull String s, @Nullable List<String> list) {
        this.config.setInlineComments(s, list);
    }
}