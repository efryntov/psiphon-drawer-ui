package com.psiphon3.subscription.ui.premium;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;
import androidx.transition.Scene;
import androidx.transition.TransitionManager;

import com.android.billingclient.api.SkuDetails;
import com.psiphon3.billing.BillingRepository;
import com.psiphon3.billing.PlayStoreBillingViewModel;
import com.psiphon3.billing.SubscriptionState;

import java.util.List;

import com.psiphon3.subscription.R;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;

public class PremiumOptionsFragment extends Fragment {
    private PlayStoreBillingViewModel billingViewModel;
    private Scene sceneSubscriber;
    private Scene sceneNotSubscriber;
    private ViewGroup sceneRoot;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();


    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        billingViewModel = ViewModelProviders.of(getActivity()).get(PlayStoreBillingViewModel.class);
        View root = inflater.inflate(R.layout.fragment_premium_options, container, false);

        sceneRoot = root.findViewById(R.id.scene_root);
        sceneSubscriber = Scene.getSceneForLayout(sceneRoot, R.layout.scene_premium_options_subscriber, getActivity());
        sceneNotSubscriber = Scene.getSceneForLayout(sceneRoot, R.layout.scene_premium_options_not_subscriber, getActivity());

        billingViewModel.subscriptionStatusFlowable()
                .distinctUntilChanged()
                .doOnNext(subscriptionState -> {
                    if(subscriptionState.hasValidPurchase()) {
                        sceneSubscriber.setEnterAction(() -> renderSubscriberScreen(root, subscriptionState));
                        TransitionManager.go(sceneSubscriber);
                    } else {
                        sceneNotSubscriber.setEnterAction(() ->
                                compositeDisposable.add(
                                        billingViewModel.allSkuDetailsSingle()
                                                .toObservable()
                                                .flatMap(Observable::fromIterable)
                                                .filter(skuDetails -> {
                                                    String sku = skuDetails.getSku();
                                                    return BillingRepository.IAB_TIMEPASS_SKUS_TO_DAYS.containsKey(sku) ||
                                                            sku.equals(BillingRepository.IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU) ||
                                                            sku.equals(BillingRepository.IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU);
                                                })
                                                .map(SkuDetails::getOriginalJson)
                                                .toList()
                                                .doOnSuccess(skuDetails -> renderPurchasePremiumOptions(root, skuDetails))
                                                .subscribe()
                                ));
                        TransitionManager.go(sceneNotSubscriber);
                    }
                })
                .subscribe();


        return root;
    }

    private void renderPurchasePremiumOptions(View root, List<String> skuDetails) {
        TextView tv = root.findViewById(R.id.textView);
        tv.setText(skuDetails.toString());
    }

    private void renderSubscriberScreen(View root, SubscriptionState subscriptionState) {
        TextView tv = root.findViewById(R.id.textView);
        tv.setText(subscriptionState.toString());
    }

    @Override
    public void onResume() {
        super.onResume();
        billingViewModel.queryAllSkuDetails();
        billingViewModel.queryCurrentSubscriptionStatus();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
    }
}