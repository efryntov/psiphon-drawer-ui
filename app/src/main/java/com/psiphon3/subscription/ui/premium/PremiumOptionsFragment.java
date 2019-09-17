package com.psiphon3.subscription.ui.premium;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;
import androidx.transition.Scene;
import androidx.transition.TransitionManager;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.SkuDetails;
import com.psiphon3.Utils;
import com.psiphon3.billing.BillingRepository;
import com.psiphon3.billing.PlayStoreBillingViewModel;
import com.psiphon3.billing.SubscriptionState;

import java.text.NumberFormat;
import java.util.Currency;
import java.util.List;

import com.psiphon3.subscription.R;
import com.psiphon3.Utils.MyLog;

import org.json.JSONException;

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

    private void renderPurchasePremiumOptions(View root, List<String> jsonSkuDetailsList) {
        for (String jsonSkuDetails : jsonSkuDetailsList) {
            SkuDetails skuDetails;
            try {
                skuDetails = new SkuDetails(jsonSkuDetails);
            } catch (JSONException e) {
                MyLog.g("PaymentChooserActivity: error parsing SkuDetails: " + e);
                continue;
            }

            int buttonResId = 0;
            float pricePerDay = 0f;

            // Calculate life time in days for subscriptions
            if (skuDetails.getType().equals(BillingClient.SkuType.SUBS)) {
                if (skuDetails.getSubscriptionPeriod().equals("P1W")) {
                    pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / 7f;
                } else if (skuDetails.getSubscriptionPeriod().equals("P1M")) {
                    pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / (365f / 12);
                } else if (skuDetails.getSubscriptionPeriod().equals("P3M")) {
                    pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / (365f / 4);
                } else if (skuDetails.getSubscriptionPeriod().equals("P6M")) {
                    pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / (365f / 2);
                } else if (skuDetails.getSubscriptionPeriod().equals("P1Y")) {
                    pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / 365f;
                }
                if (pricePerDay == 0f) {
                    MyLog.g("PaymentChooserActivity error: bad subscription period for sku: " + skuDetails);
                    return;
                }

                // Get button resource ID
                if (skuDetails.getSku().equals(BillingRepository.IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU)) {
                    buttonResId = R.id.limitedSubscription;
                } else if (skuDetails.getSku().equals(BillingRepository.IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU)) {
                    buttonResId = R.id.unlimitedSubscription;
                }
            } else {
                String timepassSku = skuDetails.getSku();

                // Get pre-calculated life time in days for time passes
                Long lifetimeInDays = BillingRepository.IAB_TIMEPASS_SKUS_TO_DAYS.get(timepassSku);
                if (lifetimeInDays == null || lifetimeInDays == 0L) {
                    Utils.MyLog.g("PaymentChooserActivity error: unknown timepass period for sku: " + skuDetails);
                    continue;
                }
                // Get button resource ID
                buttonResId = getResources().getIdentifier("timepass" + lifetimeInDays, "id", getActivity().getPackageName());
                pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / lifetimeInDays;
            }

            if (buttonResId == 0) {
                MyLog.g("PaymentChooserActivity error: no button resource for sku: " + skuDetails);
                continue;
            }
            Button button = root.findViewById(buttonResId);

            // If the formatting for pricePerDayText fails below, use this as a default.
            String pricePerDayText = skuDetails.getPriceCurrencyCode() + " " + pricePerDay;

            try {
                Currency currency = Currency.getInstance(skuDetails.getPriceCurrencyCode());
                NumberFormat priceFormatter = NumberFormat.getCurrencyInstance();
                priceFormatter.setCurrency(currency);
                pricePerDayText = priceFormatter.format(pricePerDay);
            } catch (IllegalArgumentException e) {
                // do nothing
            }

            String formatString = button.getText().toString();
            String buttonText = String.format(formatString, skuDetails.getPrice(), pricePerDayText);
            button.setText(buttonText);
            button.setVisibility(View.VISIBLE);
            button.setOnClickListener(v -> {
                Utils.MyLog.g("PaymentChooserActivity purchase button clicked.");
                billingViewModel.launchFlow(getActivity(), skuDetails).subscribe();
            });
        }
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