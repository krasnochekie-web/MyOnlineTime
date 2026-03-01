package com.mynewtime.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.mynewtime.app.MainActivity;
import com.mynewtime.app.R;
import com.mynewtime.app.VpsApi;
import com.mynewtime.app.adapters.UserListAdapter;
import com.mynewtime.app.models.User;

import java.util.List;

public class FollowsListFragment extends Fragment {

    private String targetUid;
    private String listType; // "followers" или "following"
    private UserListAdapter adapter;
    private TextView statusText;

    public static FollowsListFragment newInstance(String uid, String type) {
        FollowsListFragment f = new FollowsListFragment();
        Bundle args = new Bundle();
        args.putString("UID", uid);
        args.putString("TYPE", type);
        f.setArguments(args);
        return f;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.layout_follows_list, container, false);
        
        targetUid = getArguments().getString("UID");
        listType = getArguments().getString("TYPE");

        RecyclerView recyclerView = view.findViewById(R.id.follows_results_list);
        statusText = view.findViewById(R.id.follows_status_text);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new UserListAdapter((MainActivity) getActivity());
        recyclerView.setAdapter(adapter);

        loadData();

        return view;
    }

    private void loadData() {
        final MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        statusText.setVisibility(View.VISIBLE);
        statusText.setText(getString(R.string.loading));

        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(activity);
        if (acct != null) {
            VpsApi.authenticateWithGoogle(acct.getIdToken(), new VpsApi.LoginCallback() {
                @Override
                public void onSuccess(String token) {
                    VpsApi.getList(token, targetUid, listType, new VpsApi.SearchCallback() {
                        @Override public void onFound(List<User> users) {
                            if (!isAdded()) return;
                            if (users == null || users.isEmpty()) {
                                statusText.setVisibility(View.VISIBLE);
                                statusText.setText(getString(R.string.empty_list));
                            } else {
                                statusText.setVisibility(View.GONE);
                                adapter.setUsers(users);
                            }
                        }
                    });
                }
                @Override public void onError(String error) {
                    if (!isAdded()) return;
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText(getString(R.string.err_loading));
                }
            });
        }
    }
}
