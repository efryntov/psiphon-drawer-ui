package com.psiphon3.subscription.ui.home;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;

import com.psiphon3.EmbeddedValues;
import com.psiphon3.TunnelManager;
import com.psiphon3.TunnelServiceInteractor;

import java.util.ArrayList;

import com.psiphon3.subscription.R;

import static android.app.Activity.RESULT_OK;

public class HomeFragment extends Fragment {

    private static final int REQUEST_CODE_PREPARE_VPN = 111;
    private HomeViewModel homeViewModel;
    private TunnelServiceInteractor tunnelServiceInteractor;
    private boolean m_loadedSponsorTab;
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        tunnelServiceInteractor = new TunnelServiceInteractor(getContext());

        homeViewModel =
                ViewModelProviders.of(this).get(HomeViewModel.class);
        View root = inflater.inflate(R.layout.fragment_home, container, false);
        final Button toggleButton = root.findViewById(R.id.vpn_toggle_button);
        final Button browserButton = root.findViewById(R.id.open_browser_button);

        tunnelServiceInteractor.tunnelStateFlowable()
                .doOnNext(state -> {
                    if (state.isUnknown()) {
                        toggleButton.setEnabled(false);
                        browserButton.setEnabled(false);
                        toggleButton.setText("Waiting...");
                    } else if (state.isRunning()) {
                        toggleButton.setEnabled(true);
                        if(state.connectionData().isConnected()) {
                            ArrayList<String> homePages = state.connectionData().homePages();
                            browserButton.setEnabled(true);
                            final String url;
                            if(homePages != null  && homePages.size() > 0) {
                                url = homePages.get(0);
                            } else {
                                url = null;
                            }
                            browserButton.setOnClickListener(v -> displayBrowser(getContext(), url));

                        } else {
                            browserButton.setEnabled(false);
                            browserButton.setOnClickListener(null);
                        }
                        toggleButton.setText("Stop");
                        toggleButton.setOnClickListener(v -> stopVpn());
                    } else {
                        browserButton.setEnabled(false);
                        toggleButton.setEnabled(true);
                        toggleButton.setText("Start");
                        toggleButton.setOnClickListener(v -> startVPN());
                    }
                })
                .subscribe();
        return root;
    }

    void displayBrowser(Context context, String urlString) {
        // PsiCash modify URLs by default
        displayBrowser(context, urlString, true);
    }

    public void displayBrowser(Context context, String urlString, boolean shouldPsiCashModifyUrls) {
        if (shouldPsiCashModifyUrls) {
            // Add PsiCash parameters
//            urlString = PsiCashModifyUrl(urlString);
        }

        // Notify PsiCash fragment so it will know to refresh state on next app foreground.
//        psiCashFragment.onOpenHomePage();

        try {
            // TODO: support multiple home pages in whole device mode. This is
            // disabled due to the case where users haven't set a default browser
            // and will get the prompt once per home page.

            // If URL is not empty we will try to load in an external browser, otherwise we will
            // try our best to open an external browser instance without specifying URL to load
            // or will load "about:blank" URL if that fails.

            // Prepare browser starting intent.
            Intent browserIntent;
            if (TextUtils.isEmpty(urlString)) {
                // If URL is empty, just start the app.
                browserIntent = new Intent(Intent.ACTION_MAIN);
            } else {
                // If URL is not empty, start the app with URL load intent.
                browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
            }
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // query default 'URL open' intent handler.
            Intent queryIntent;
            if (TextUtils.isEmpty(urlString)) {
                queryIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.org"));
            } else {
                queryIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
            }
            ResolveInfo resolveInfo = getActivity().getPackageManager().resolveActivity(queryIntent, PackageManager.MATCH_DEFAULT_ONLY);

            // Try and start default intent handler application if there is one
            if (resolveInfo != null &&
                    resolveInfo.activityInfo != null &&
                    resolveInfo.activityInfo.name != null &&
                    !resolveInfo.activityInfo.name.toLowerCase().contains("resolver")) {
                browserIntent.setClassName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name);
                context.startActivity(browserIntent);
            } else { // There is no default handler, try chrome
                browserIntent.setPackage("com.android.chrome");
                try {
                    context.startActivity(browserIntent);
                } catch (ActivityNotFoundException ex) {
                    // We tried to open Chrome and it is not installed,
                    // so reinvoke with the default behaviour
                    browserIntent.setPackage(null);
                    // If URL is empty try loading a special URL 'about:blank'
                    if (TextUtils.isEmpty(urlString)) {
                        browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("about:blank"));
                    }
                    context.startActivity(browserIntent);
                }
            }
        } catch (ActivityNotFoundException e) {
            // Thrown by startActivity; in this case, we ignore and the URI isn't opened
        }
    }


    protected boolean shouldLoadInEmbeddedWebView(String url) {
        for (String homeTabUrlExclusion : EmbeddedValues.HOME_TAB_URL_EXCLUSIONS) {
            if (url.contains(homeTabUrlExclusion)) {
                return false;
            }
        }
        return true;
    }


    private void stopVpn() {
        tunnelServiceInteractor.stopTunnelService();
    }

    @Override
    public void onResume() {
        super.onResume();
        tunnelServiceInteractor.resume(getContext());

        Bundle data = getArguments();
        if (data != null) {
            ArrayList<String> homePages = data.getStringArrayList(TunnelManager.DATA_TUNNEL_STATE_HOME_PAGES);
            if (homePages != null && homePages.size() > 0) {
                String url = homePages.get(0);
                // At this point we're showing the URL in either the embedded webview or in a browser.
                // Some URLs are excluded from being embedded as home pages.
                if (shouldLoadInEmbeddedWebView(url)) {
                    // Reset m_loadedSponsorTab and switch to the home tab.
                    // The embedded web view will get loaded by the updateServiceStateUI.
                    m_loadedSponsorTab = false;
                } else {
                    displayBrowser(getActivity().getApplicationContext(), url);
                }
            }
            setArguments(null);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        tunnelServiceInteractor.pause(getContext());
    }

    private void startVPN() {
        Intent vpnIntent = VpnService.prepare(getActivity());
        if (vpnIntent != null)
            startActivityForResult(vpnIntent, REQUEST_CODE_PREPARE_VPN);
        else
            onActivityResult(REQUEST_CODE_PREPARE_VPN, RESULT_OK, null);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PREPARE_VPN && resultCode == RESULT_OK) {
            TunnelManager.Config tunnelConfig = new TunnelManager.Config();
            tunnelServiceInteractor.startTunnelService(getActivity().getApplicationContext(), tunnelConfig);
        }
    }
}