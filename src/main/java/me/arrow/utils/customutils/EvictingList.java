package me.arrow.utils.customutils;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

@Getter
public class EvictingList<T> extends LinkedList<T> {
    int maxSize;

    public EvictingList(int maxSize) {
        this.maxSize = maxSize;
    }

    public EvictingList(Collection<? extends T> c, int maxSize) {
        super(c);
        this.maxSize = maxSize;
    }

    @Override
    public boolean add(T t) {
        if (this.size() >= this.maxSize) {
            this.removeFirst();
        }

        return super.add(t);
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        return c.stream().anyMatch(this::add);
    }

    @Override
    public @NotNull Stream<T> stream() {
        return new CopyOnWriteArrayList<>(this).stream();
    }

    public boolean isFull() {
        return size() >= getMaxSize();
    }
}
