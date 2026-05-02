package com.myonlinetime.app.ui;

import androidx.fragment.app.Fragment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar; 

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.myonlinetime.app.MainActivity;
import com.myonlinetime.app.R;
import com.myonlinetime.app.VpsApi;
import com.myonlinetime.app.models.User;
import com.myonlinetime.app.adapters.UserListAdapter;

import java.util.ArrayList;
import java.util.List;

public class SearchFragment extends Fragment {

    private String lastSearchQuery = "";
    private RecyclerView resultsList; 
    private UserListAdapter adapter;  
    private ProgressBar loadingSpinner; 
    
    private Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    public SearchFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerManager.resetHeader();
            
            // Просто ставим фон на паузу/прячем
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isAdded() && !isHidden()) activity.updateGlobalBackground(false);
            }, 400);
        }

        View view = inflater.inflate(R.layout.layout_search, container, false);
        
        EditText searchInput = view.findViewById(R.id.search_input);
        resultsList = view.findViewById(R.id.search_results_list);
        loadingSpinner = view.findViewById(R.id.search_loading_spinner); 

        resultsList.setLayoutManager(new LinearLayoutManager(activity));
        
        GoogleSignInAccount acct = activity != null ? GoogleSignIn.getLastSignedInAccount(activity) : null;
        final String myUid = acct != null ? acct.getId() : "";

        adapter = new UserListAdapter(activity, clickedUser -> {
            if (activity != null && activity.navigator != null) {
                if (clickedUser.uid != null && clickedUser.uid.equals(myUid)) {
                    activity.navigator.switchScreen(4, myUid);
                } else {
                    activity.navigator.openSubScreen(OtherProfileFragment.newInstance(clickedUser.uid, activity.getString(R.string.title_search)));
                }
            }
        });
        
        resultsList.setAdapter(adapter);
        resultsList.setOverScrollMode(View.OVER_SCROLL_NEVER);

        if (lastSearchQuery.length() > 0) {
            searchInput.setText(lastSearchQuery);
            searchInput.setSelection(lastSearchQuery.length());
            performSearch(lastSearchQuery, activity);
        }

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                lastSearchQuery = s.toString();
                if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
                searchRunnable = () -> performSearch(s.toString(), activity);
                searchHandler.postDelayed(searchRunnable, 400);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        return view;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        if (!hidden) {
            activity.mainHeader.setVisibility(View.VISIBLE);
            activity.headerManager.resetHeader();
            
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isAdded() && !isHidden()) activity.updateGlobalBackground(false);
            }, 400);
        }
    }

    private void performSearch(final String query, final MainActivity activity) {
        if(query.trim().length() > 0) {
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
            if (activity.vpsToken != null && !activity.vpsToken.isEmpty()) {
                executeSearchApi(activity.vpsToken, query);
            } else {
                if (activity.mGoogleSignInClient != null) {
                    activity.mGoogleSignInClient.silentSignIn().addOnSuccessListener(freshAccount -> {
                        VpsApi.authenticateWithGoogle(activity, freshAccount.getIdToken(), new VpsApi.LoginCallback() {
                            @Override public void onSuccess(String token) { activity.vpsToken = token; executeSearchApi(token, query); }
                            @Override public void onError(String e) { if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE); }
                        });
                    }).addOnFailureListener(e -> {
                        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
                        if(acct != null) {
                            VpsApi.authenticateWithGoogle(activity, acct.getIdToken(), new VpsApi.LoginCallback() {
                                @Override public void onSuccess(String token) { activity.vpsToken = token; executeSearchApi(token, query); }
                                @Override public void onError(String ex) { if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE); }
                            });
                        }
                    });
                }
            }
        } else {
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
            adapter.setUsers(new ArrayList<>());
        }
    }

    private void executeSearchApi(String token, String query) {
        VpsApi.searchUsers(token, query, new VpsApi.SearchCallback() {
            @Override public void onFound(List<User> users) {
                if (!isAdded()) return; 
                if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
                adapter.setUsers(users);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isAdded() && !isHidden()) activity.updateGlobalBackground(false);
            }, 400);
        }
    }
}
