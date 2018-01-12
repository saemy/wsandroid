package fi.bitrite.android.ws.ui;

import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import butterknife.BindColor;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnItemClick;
import fi.bitrite.android.ws.R;
import fi.bitrite.android.ws.WSAndroidApplication;
import fi.bitrite.android.ws.api.RestClient;
import fi.bitrite.android.ws.api_new.AuthenticationController;
import fi.bitrite.android.ws.host.Search;
import fi.bitrite.android.ws.host.impl.RestTextSearch;
import fi.bitrite.android.ws.model.Host;
import fi.bitrite.android.ws.repository.Resource;
import fi.bitrite.android.ws.repository.UserRepository;
import fi.bitrite.android.ws.ui.listadapter.UserListAdapter;
import fi.bitrite.android.ws.ui.util.DialogHelper;
import fi.bitrite.android.ws.ui.util.NavigationController;
import fi.bitrite.android.ws.ui.util.ProgressDialog;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.subjects.BehaviorSubject;

public class SearchFragment extends BaseFragment {

    private static final String KEY_QUERY = "query";
    private static final String KEY_USER_IDS = "user_ids";

    @Inject AuthenticationController mAuthenticationController;
    @Inject NavigationController mNavigationController;
    @Inject UserRepository mUserRepository;

    @BindView(R.id.search_lst_result) ListView mLstSearchResult;
    @BindColor(R.color.primaryColorAccent) int mDecoratorForegroundColor;

    private UserListAdapter mSearchResultListAdapter;
    private TextSearchTask mTextSearchTask;

    private BehaviorSubject<ArrayList<Integer>> mUserIds;
    private String mQuery;

    private ProgressDialog.Disposable mProgressDisposable;

    public static Fragment create(String query) {
        Bundle bundle = new Bundle();
        bundle.putString(KEY_QUERY, query);

        Fragment fragment = new SearchFragment();
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle icicle) {
        View view = inflater.inflate(R.layout.fragment_search_result, container, false);
        ButterKnife.bind(this, view);

        mUserIds = BehaviorSubject.create();

        // Checks if we already have the search result.
        boolean searchSuccessful = icicle != null && icicle.containsKey(KEY_USER_IDS);
        if (searchSuccessful) {
            mUserIds.onNext(icicle.getIntegerArrayList(KEY_USER_IDS));
        }

        // Does the requested search if it did not finish yet.
        final Bundle args = getArguments();
        mQuery = args != null ? args.getString(KEY_QUERY) : null;
        if (mQuery != null && !searchSuccessful) {
            doTextSearch(mQuery);
        }

        // Initializes the search result list.
        Decorator decorator = new Decorator(mQuery, mDecoratorForegroundColor);
        mSearchResultListAdapter = new UserListAdapter(getContext(), mComparator, decorator);
        mLstSearchResult.setAdapter(mSearchResultListAdapter);
        mUserIds.observeOn(AndroidSchedulers.mainThread())
                .subscribe(userIds -> {
                    List<Observable<Resource<Host>>> users = mUserRepository.get(userIds);
                    mSearchResultListAdapter.resetDataset(users, 0);
                });

        return view;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        ArrayList<Integer> userIds = mUserIds.getValue();
        if (userIds != null) {
            outState.putIntegerArrayList(KEY_USER_IDS, userIds);
        }

        if (mTextSearchTask != null) {
            mTextSearchTask.cancel(true);
        }
        super.onSaveInstanceState(outState);
    }

    private void doTextSearch(String text) {
        mProgressDisposable = ProgressDialog.create(R.string.performing_search)
                .show(getActivity());

        Search search = new RestTextSearch(mAuthenticationController, text);
        mTextSearchTask = new TextSearchTask();
        mTextSearchTask.execute(search);
    }

    @OnItemClick(R.id.search_lst_result)
    public void onUserClicked(int position) {
        Host user = (Host) mLstSearchResult.getItemAtPosition(position);
        mNavigationController.navigateToUser(user.getId());
    }

    private class TextSearchTask extends AsyncTask<Search, Void, Object> {

        @Override
        protected Object doInBackground(Search... params) {
            try {
                Search search = params[0];
                return search.doSearch();
            } catch (Exception e) {
                Log.e(WSAndroidApplication.TAG, e.getMessage(), e);
                return e;
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void onPostExecute(Object result) {
            if (result instanceof Exception) {
                mProgressDisposable.dispose();

                RestClient.reportError(getContext(), result);
                return;
            }

            List<Host> searchResult = (List<Host>) result;
            ArrayList<Integer> userIds = new ArrayList<>(searchResult.size());
            for (Host user : searchResult) {
                userIds.add(user.getId());
            }
            mUserIds.onNext(userIds);

            mProgressDisposable.dispose();

            if (searchResult.isEmpty()) {
                DialogHelper.alert(getContext(), R.string.no_results);
            }
        }
    }

    @Override
    protected CharSequence getTitle() {
        CharSequence title = getString(R.string.title_fragment_search);
        if (mQuery != null) {
            title = title + ": \"" + mQuery + "\"";
        }
        return title;
    }

    private final static Comparator<Host> mComparator = (left, right) -> {
        int ncaLeft = left.isNotCurrentlyAvailable() ? 1  : 0;
        int ncaRight = right.isNotCurrentlyAvailable() ? 1 : 0;

        return ncaLeft != ncaRight
                ? ncaLeft - ncaRight
                : left.getFullname().compareTo(right.getFullname()); // TODO(saemy): Something smarter?
    };

    private static class Decorator implements UserListAdapter.Decorator {
        private final String mQuery;
        private final Pattern mQueryPattern;
        @ColorInt private int mForegroundColor;

        Decorator(String query, @ColorInt int foregroundColor) {
            mQuery = query;
            mQueryPattern = query != null
                ? Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                : null;
            mForegroundColor = foregroundColor;
        }

        @Override
        public SpannableStringBuilder decorateFullname(String fullname) {
            return mQueryPattern.matcher(fullname).find()
                    ? getTextMatch(mQuery, fullname)
                    : new SpannableStringBuilder(fullname);

//           Toast.makeText(getContext(), "HostListAdp hostFullname = " + fullname + " - " + mQuery + " - " + mQueryPattern.matcher(fullname).find(), Toast.LENGTH_SHORT).show();
        }

        @Override
        public SpannableStringBuilder decorateLocation(String location) {
            final String locationLower = location.toLowerCase();
            return mQueryPattern.matcher(locationLower).find()
                    ? getTextMatch(mQuery, locationLower)
                    : new SpannableStringBuilder(location);
        }

        private SpannableStringBuilder getTextMatch(String pattern, String match) {
            final Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            final Matcher matcher = p.matcher(match);

            // TODO: ignore accents and other special characters
            final SpannableStringBuilder spannable = new SpannableStringBuilder(match);
            final ForegroundColorSpan span = new ForegroundColorSpan(mForegroundColor);
            while (matcher.find()) {
                spannable.setSpan(
                        span, matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return spannable;
        }
    };
}
