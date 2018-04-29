package fi.bitrite.android.ws.ui;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.SparseArray;

import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import fi.bitrite.android.ws.R;
import fi.bitrite.android.ws.model.Host;
import fi.bitrite.android.ws.model.Message;
import fi.bitrite.android.ws.model.MessageThread;
import fi.bitrite.android.ws.repository.MessageRepository;
import fi.bitrite.android.ws.repository.Resource;
import fi.bitrite.android.ws.repository.UserRepository;
import fi.bitrite.android.ws.ui.listadapter.MessageListAdapter;
import fi.bitrite.android.ws.util.LoggedInUserHelper;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

@Singleton
public class MessageNotificationController {
    private final static String CHANNEL_ID = "ws_messages";
    private final static String NOTIFICATION_GROUP = CHANNEL_ID;

    private final Context mApplicationContext;
    private final LoggedInUserHelper mLoggedInUserHelper;
    private final MessageRepository mMessageRepository;
    private final UserRepository mUserRepository;

    private final NotificationManager mNotificationManager;

    private final SparseArray<NotificationEntry> mNotificationsByThread = new SparseArray<>();
    // TODO(saemy): Set with get(threadId)?

    @Inject
    MessageNotificationController(
            Context applicationContext, LoggedInUserHelper loggedInUserHelper,
            MessageRepository messageRepository, UserRepository userRepository) {
        mApplicationContext = applicationContext;
        mLoggedInUserHelper = loggedInUserHelper;
        mMessageRepository = messageRepository;
        mUserRepository = userRepository;

        mNotificationManager = (NotificationManager) mApplicationContext.getSystemService(
                Context.NOTIFICATION_SERVICE);

        // Registers for updates from the message repository. We retreive an observable list of
        // observables. As soon as the list changes, we no longer listen to changes of the old one
        // and re-register ourselves to all the observables of the new list.
        class Container {
            private Disposable mDisposable;

            private void handleUpdate(List<Observable<Resource<MessageThread>>> observables) {
                if (mDisposable != null) {
                    mDisposable.dispose();
                }
                mDisposable = handleNewThreadList(observables);
            }
        }
        final Container container = new Container();
        mMessageRepository.getAll().subscribe(container::handleUpdate);
    }

    private Disposable handleNewThreadList(List<Observable<Resource<MessageThread>>> observables) {
        return Observable.mergeDelayError(observables)
                .observeOn(Schedulers.computation())
                .filter(Resource::hasData)
                .map(resource -> resource.data)
                .filter(thread -> {
                    boolean hasNew = thread.hasNewMessages();
                    if (!hasNew) {
                        // Remove any existing notification.
                        NotificationEntry entry = mNotificationsByThread.get(thread.id);
                        if (entry != null) {
                            dismissNotification(entry);
                        }
                    }
                    return hasNew;
                    // Only unread threads from here.
                })
                .map(thread -> {
                    Collections.sort(thread.messages, MessageListAdapter.COMPARATOR);
                    return thread;
                })
                .map(this::getNotificationEntry)
                .flatMap(this::loadParticipantsIntoNotificationEntry)
                .observeOn(AndroidSchedulers.mainThread()) // Why is this needed Picasso?
                .flatMap(this::loadPartnerBitmapIntoNotificationEntry)
//                .buffer(1, TimeUnit.SECONDS) // Limit number of updates during burst.
                .subscribe(this::updateNotification);
    }

    /**
     * Returns the notification entry for given threaad.
     */
    @NonNull
    private NotificationEntry getNotificationEntry(MessageThread thread) {
        NotificationEntry entry = mNotificationsByThread.get(thread.id);
        if (entry == null) {
            entry = new NotificationEntry(thread);
            mNotificationsByThread.put(thread.id, entry);
        } else {
            entry.setThread(thread); // Thread objects are subject to change.
        }
        return entry;
    }

    /**
     * Checks whether the author needs to be fetched from the users repository for the given
     * notification entry
     * @return An observable (actually a single) that fires as soon as the entry contains the user.
     */
    private Observable<NotificationEntry> loadParticipantsIntoNotificationEntry(
            NotificationEntry entry) {
        // Loads the author of the message.
        SparseArray<Host> participants = entry.participants;

        if (participants.size() == 0) { // TODO(saemy): Does not support changing participant list.
            // Fetches the participating users from the repository.
            return Observable.mergeDelayError(mUserRepository.get(entry.thread.participantIds))
                    .filter(Resource::hasData)
                    .map(hostResource -> {
                        Host user = hostResource.data;
                        entry.participants.append(user.getId(), user);
                        return entry;
                    })
                    // Only fire once we loaded all participants.
                    .filter(e -> e.participants.size() == e.thread.participantIds.size());
        } else {
            return Observable.just(entry);
        }
    }

    private Observable<NotificationEntry> loadPartnerBitmapIntoNotificationEntry(
            NotificationEntry entry) {
        return Observable.create(e -> {
            if (entry.partnerProfileBitmap != null || entry.participants.size() != 2) {
                e.onNext(entry);
                return;
            }

            // Gets the partner (not our) user element.
            Host partner = entry.participants.valueAt(0);
            if (partner.getId() == mLoggedInUserHelper.getId()) {
                partner = entry.participants.valueAt(1);
            }

            String pictureUrl = partner.getProfilePictureSmall();
            if (pictureUrl != null) {
                Target target = new Target() {
                    @Override
                    public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                        entry.partnerProfileBitmap = bitmap;
                        e.onNext(entry);
                    }

                    @Override
                    public void onBitmapFailed(Drawable errorDrawable) {
                        // Ignore the error.
                        e.onNext(entry);
                    }

                    @Override
                    public void onPrepareLoad(Drawable placeHolderDrawable) {
                    }
                };

                Picasso.with(mApplicationContext)
                        .load(pictureUrl)
                        .into(target);
            } else {
                e.onNext(entry);
            }
        });
    }

    private void updateNotification(NotificationEntry entry) {
        // Removes the notification if nothing is to be notified about.
        if (entry.latestNewMessage == null) {
            dismissNotification(entry);
            return;
        }

        Intent resultIntent = MainActivity.createForMessageThread(
                mApplicationContext, entry.thread.id);

        // The stack builder object will contain an artificial back stack for the
        // started Activity.
        // This ensures that navigating backward from the Activity leads out of
        // your app to the Home screen.
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(mApplicationContext);
        // Adds the back stack for the Intent (but not the Intent itself)
        stackBuilder.addParentStack(MainActivity.class);
        // Adds the Intent that starts the Activity to the top of the stack
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(
                0, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? createNotification(entry, resultPendingIntent)
                : createNotificationBeforeApi24(entry, resultPendingIntent);

        mNotificationManager.notify(entry.notificationId(), notification);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private Notification createNotification(NotificationEntry entry, PendingIntent intent) {
        Host us = mLoggedInUserHelper.get();
        if (us == null) {
            return createNotificationBeforeApi24(entry, intent);
        }

        Notification.MessagingStyle style = new Notification.MessagingStyle(us.getFullname())
                .setConversationTitle(entry.thread.subject);
        for (Message message : entry.thread.messages) {
            String authorName = entry.participants.get(message.authorId).getFullname();
            Notification.MessagingStyle.Message msg = new Notification.MessagingStyle.Message(
                    message.body, message.date.getTime(), authorName);
            if (message.isNew) {
                style.addMessage(msg);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                style.addHistoricMessage(msg);
            }
        }

        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(mApplicationContext, CHANNEL_ID)
                        : new Notification.Builder(mApplicationContext))
                .setSmallIcon(R.drawable.ic_bicycle_white_24dp)
                .setLargeIcon(entry.partnerProfileBitmap)
                .setStyle(style)
                .setContentIntent(intent)
                .setGroup(NOTIFICATION_GROUP)
                .build();
    }

    private Notification createNotificationBeforeApi24(NotificationEntry entry,
                                                       PendingIntent intent) {
        NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
        for (Message message : entry.thread.messages) {
            if (!message.isNew) {
                continue;
            }
            style.addLine(message.body);
        }

        assert entry.latestNewMessage != null;
        String newestMessageAuthorName =
                entry.participants.get(entry.latestNewMessage.authorId).getFullname();
        return new NotificationCompat.Builder(mApplicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bicycle_white_24dp)
                .setLargeIcon(entry.partnerProfileBitmap)
                .setStyle(style)
                .setContentTitle(newestMessageAuthorName)
                .setContentText(entry.latestNewMessage.body)
                .setContentIntent(intent)
                .setGroup(NOTIFICATION_GROUP)
                .build();
    }

    private void dismissNotification(@NonNull NotificationEntry entry) {
        mNotificationManager.cancel(entry.notificationId());
    }


    /**
     * Per-thread structure that keeps required data for a notification around.
     */
    private static class NotificationEntry {
        @NonNull MessageThread thread;
        @Nullable Message latestNewMessage;
        @NonNull final SparseArray<Host> participants = new SparseArray<>();
        @Nullable Bitmap partnerProfileBitmap;

        NotificationEntry(@NonNull MessageThread thread) {
            setThread(thread);
        }

        void setThread(@NonNull MessageThread thread) {
            this.thread = thread;
            setLatestNewMessage();
        }

        int notificationId() {
            // We just use the thread id as the notification id.
            return thread.id;
        }

        private void setLatestNewMessage() {
            latestNewMessage = null;
            for (Message message : thread.messages) {
                if (message.isNew) {
                    latestNewMessage = message;
                }
            }
        }
    }
}