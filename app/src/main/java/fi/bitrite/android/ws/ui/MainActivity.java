package fi.bitrite.android.ws.ui;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.squareup.picasso.Picasso;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import dagger.android.DispatchingAndroidInjector;
import dagger.android.support.HasSupportFragmentInjector;
import fi.bitrite.android.ws.R;
import fi.bitrite.android.ws.api.AuthenticationController;
import fi.bitrite.android.ws.model.Host;
import fi.bitrite.android.ws.repository.MessageRepository;
import fi.bitrite.android.ws.repository.Resource;
import fi.bitrite.android.ws.ui.listadapter.NavigationListAdapter;
import fi.bitrite.android.ws.ui.model.NavigationItem;
import fi.bitrite.android.ws.ui.util.ActionBarTitleHelper;
import fi.bitrite.android.ws.ui.util.NavigationController;
import fi.bitrite.android.ws.util.LoggedInUserHelper;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class MainActivity extends AppCompatActivity implements HasSupportFragmentInjector {

    private static final String KEY_MESSAGE_THREAD_ID = "thread_id";

    private final NavigationItem mMessageNavigationItem = new NavigationItem(
            NavigationController.NAVIGATION_TAG_MESSAGE_THREADS, R.drawable.ic_message_grey600_24dp,
            R.string.navigation_item_messages);
    private final List<NavigationItem> mPrimaryNavigationItems = Arrays.asList(
            // Map
            new NavigationItem(NavigationController.NAVIGATION_TAG_MAP,
                    R.drawable.ic_map_grey600_24dp, R.string.navigation_item_map),

            // Starred hosts
            new NavigationItem(NavigationController.NAVIGATION_TAG_FAVORITE_USERS,
                    R.drawable.ic_favorite_grey600_24dp, R.string.navigation_item_favorites),

            // Messages
           mMessageNavigationItem
    );
    private final List<NavigationItem> mSecondaryNavigationItems = Arrays.asList(
            // Settings
            new NavigationItem(NavigationController.NAVIGATION_TAG_SETTINGS,
                    R.drawable.ic_settings_grey600_24dp, R.string.navigation_item_settings),

            // About
            new NavigationItem(NavigationController.NAVIGATION_TAG_ABOUT,
                    R.drawable.ic_info_grey600_24dp, R.string.navigation_item_about)
    );

    @Inject ActionBarTitleHelper mActionBarTitleHelper;
    @Inject DispatchingAndroidInjector<Fragment> mDispatchingAndroidInjector;

    @Inject AuthenticationController mAuthenticationController;
    @Inject LoggedInUserHelper mLoggedInUserHelper;
    @Inject MessageRepository mMessageRepository;
    @Inject NavigationController mNavigationController;

    @BindView(R.id.main_layout_drawer) DrawerLayout mMainLayout;
    @BindView(R.id.main_toolbar) Toolbar mToolbar;

    @BindView(R.id.nav_img_user_photo) ImageView mImgUserPhoto;
    @BindView(R.id.nav_lbl_fullname) TextView mLblFullname;
    @BindView(R.id.nav_lbl_username) TextView mLblUsername;
    @BindView(R.id.nav_lst_primary_navigation) ListView mPrimaryNavigationList;
    @BindView(R.id.nav_lst_secondary_navigation) ListView mSecondaryNavigationList;

    private ActionBarDrawerToggle mDrawerToggle;

    private CompositeDisposable mDisposables;

    /**
     * Creates an intent that sends, when used, the user to that message thread. This is needed for
     * notifications.
     */
    public static Intent createForMessageThread(Context context, int threadId) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(KEY_MESSAGE_THREAD_ID, threadId);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        setSupportActionBar(mToolbar);

        // We need to decorate the home button (burger) as up. This is only needed because in the
        // ActionBarDrawerToggle constructor that icon (the back arrow) is remembered as the one
        // that is shown when we disable the drawer indicator (we do this when the home button
        // should act as us and clicking it should not open the drawer menu). Afterwards, the home
        // button is decorated as a burger again.
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        mDrawerToggle = new ActionBarDrawerToggle(this, mMainLayout, mToolbar,
                R.string.drawer_open, R.string.drawer_close);
        // The drawer toggle breaks the click listener on the home button (when it is displayed as
        // up). We fix that.
        mDrawerToggle.setToolbarNavigationClickListener(view -> onSupportNavigateUp());
        mDrawerToggle.getDrawerArrowDrawable()
                .setColor(getResources().getColor(android.R.color.white));
        mDrawerToggle.setDrawerSlideAnimationEnabled(false);


        mMainLayout.addDrawerListener(mDrawerToggle);

        // Sets up the navigation. It is divided in different groups which are shown below
        // each other.
        //
        // Primary navigation.
        NavigationListAdapter primaryNavigationListAdapter = NavigationListAdapter.create(
                this, mPrimaryNavigationItems,
                mNavigationController.getTopLevelNavigationItemTag());
        primaryNavigationListAdapter.getOnClickSubject().subscribe(this::onNavigationItemClicked);
        mPrimaryNavigationList.setAdapter(primaryNavigationListAdapter);

        // Secondary navigation.
        NavigationListAdapter secondaryNavigationListAdapter = NavigationListAdapter.create(
                this, mSecondaryNavigationItems,
                mNavigationController.getTopLevelNavigationItemTag());
        secondaryNavigationListAdapter.getOnClickSubject().subscribe(this::onNavigationItemClicked);
        mSecondaryNavigationList.setAdapter(secondaryNavigationListAdapter);


        // Listen for changes in the back stack
        getSupportFragmentManager().addOnBackStackChangedListener(this::onFragmentBackStackChanged);
        onFragmentBackStackChanged();

        // Initializes the authentication controller.
        if (!mAuthenticationController.isInitialized()) {
            mAuthenticationController.init(this);
        }

        if (savedInstanceState == null) {
            mNavigationController.navigateToMainFragment();
        }

        handleActionIntents(getIntent());
    }

    @Override
    public void onResume() {
        super.onResume();

        mDisposables = new CompositeDisposable();

        // Listens for logged-in user changes.
        mDisposables.add(mLoggedInUserHelper.getRx()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(nullableUser -> {
                    if (nullableUser.isNonNull()) {
                        Host loggedInUser = nullableUser.data;

                        mLblFullname.setText(loggedInUser.getFullname());
                        mLblUsername.setText(loggedInUser.getName());

                        String profilePhotoUrl = loggedInUser.getPicture();
                        if (TextUtils.isEmpty(profilePhotoUrl)) {
                            mImgUserPhoto.setImageResource(R.drawable.default_hostinfo_profile);
                        } else {
                            Picasso.with(this)
                                    .load(profilePhotoUrl)
                                    .into(mImgUserPhoto); // largeUrl
                        }
                    } else {
                        // TODO(saemy): What to show?
                    }
                }));

        // Listens for ActionBar title changes.
        mDisposables.add(mActionBarTitleHelper.get()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(mToolbar::setTitle));

        mDisposables.add(handleNewMessageThreadCount());
    }

    @Override
    public void onPause() {
        mDisposables.dispose();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        handleActionIntents(intent);
    }
    private void handleActionIntents(Intent intent) {
        handleSearchIntent(intent);
        handleMessageThreadIntent(intent);
    }

    private void handleSearchIntent(Intent intent) {
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            mNavigationController.navigateToSearch(query);
        }
    }
    private void handleMessageThreadIntent(Intent intent) {
        int threadId = intent.getIntExtra(KEY_MESSAGE_THREAD_ID, -1);
        if (threadId != -1) {
            // TODO(saemy): Ensure back action is to message threads.
            mNavigationController.navigateToMessageThread(threadId);
        }
    }

    @Override
    public DispatchingAndroidInjector<Fragment> supportFragmentInjector() {
        return mDispatchingAndroidInjector;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDrawerToggle.onConfigurationChanged(newConfig);
    }

    private void onFragmentBackStackChanged() {
        boolean showHomeAsUp = mNavigationController.isShowHomeAsUp();

        // We disable the drawer indicator if we want to show the up action. This decorates the home
        // button as a back arrow and clicking it won't show the drawer menu anymore.
        mDrawerToggle.setDrawerIndicatorEnabled(!showHomeAsUp);
    }

    @Override
    public void onBackPressed() {
        if (mMainLayout.isDrawerOpen(Gravity.START | Gravity.LEFT)) {
            mMainLayout.closeDrawers();
            return;
        }

        super.onBackPressed();
    }

    @Override
    public boolean onSupportNavigateUp() {
        // This method is called when the up button in the toolbar is pressed. We pop the back stack.
        if (super.onSupportNavigateUp()) {
            return true;
        }

        // FIXME(saemy): This should move one level up in the tag-uri of the navigation controller.
        getSupportFragmentManager().popBackStack();
        return true;
    }

    private void onNavigationItemClicked(NavigationItem navigationItem) {
        final String itemTag = navigationItem.tag;

        // No action if this is the current fragment.
        if (itemTag.equals(mNavigationController.getTopLevelNavigationItemTag().getValue())) {
            return;
        }

        // Closes the drawer.
        mMainLayout.closeDrawers();

        // Shows the fragment.
        mNavigationController.navigateToTag(itemTag);
    }

    /**
     * Registers itself for message thread updates and keeps the notification count on the message
     * navigation item up to date.
     */
    private Disposable handleNewMessageThreadCount() {
        class Container {
            // We need a final object.
            Disposable disposable;
        }
        Container container = new Container();
        Set<Integer> newThreads = new HashSet<>();
        return mMessageRepository
                .getAll()
                .subscribe(messageThreadObservables -> {
                    if (container.disposable != null) {
                        container.disposable.dispose();
                    }
                    container.disposable = Observable.mergeDelayError(messageThreadObservables)
                            .observeOn(Schedulers.computation())
                            .filter(Resource::hasData)
                            .map(resource -> resource.data)
                            // The next filter returns true if the new-status changed.
                            .filter(thread -> thread.hasNewMessages()
                                    ? newThreads.add(thread.id)
                                    : newThreads.remove(thread.id))
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(thread -> mMessageNavigationItem.notificationCount.onNext(
                                    newThreads.size()));
                });
    }
}