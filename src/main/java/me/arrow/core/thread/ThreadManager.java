package me.arrow.core.thread;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A simple thread manager class that'll help us make sure a player profile is provided with the best
 * Available thread at any time, While also shutting down threads that are not used.
 */
public final class ThreadManager {

    //Get a proper thread limit
    private static final int MAX_THREADS = Runtime.getRuntime().availableProcessors() * 2;

    private final List<ProfileThread> profileThreads = new ArrayList<>();

    public ProfileThread getAvailableProfileThread() {

        ProfileThread profileThread;

        //Check whether or not we should create a new thread based on the thread limit
        if (this.profileThreads.size() < MAX_THREADS) {

            //Create a new profile thread and set it to our variable in order to use it
            profileThread = new ProfileThread();

            //Add our new profile thread to the list in order to use it for future profiles
            this.profileThreads.add(profileThread);

        } else {

            //Get an available thread based on the profiles using it, Otherwise grab a random element to avoid issues.
            profileThread = this.profileThreads
                    .stream()
                    .min(Comparator.comparing(ProfileThread::getProfileCount))
                    .orElse(this.profileThreads.get(ThreadLocalRandom.current()
                            .nextInt(this.profileThreads.size())));
        }

        //Throw an exception if the profile thread is null, Which should be impossible.
        if (profileThread == null) {

            throw new IllegalStateException("No profile thread is available");
        }

        //Return the available thread and increment the profile count
        return profileThread.incrementAndGet();
    }

    /** Releases a profile's worker after either backend reports that it left. */
    public void release(ProfileThread profileThread) {
        if (profileThread == null) return;

        /*
        If this is the only profile using this thread, Shut it down and remove it from the list
        Otherwise decrease the counter and return.
        */
        if (profileThread.getProfileCount() > 1) {

            profileThread.decrement();

            return;
        }

        this.profileThreads.remove(profileThread.shutdownThread());
    }

    public void shutdown() {
        this.profileThreads.forEach(ProfileThread::shutdownThread);

        this.profileThreads.clear();
    }
}
