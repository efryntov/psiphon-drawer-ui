/*
 *
 * Copyright (c) 2019, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.billing;

import android.app.Activity;
import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.psiphon3.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;

public class PlayStoreBillingViewModel extends AndroidViewModel {
    private BillingRepository repository;
    private CompositeDisposable compositeDisposable;
    private BehaviorRelay<SubscriptionState> subscriptionStateBehaviorRelay;
    private BehaviorRelay<List<SkuDetails>> skuDetailsBehaviorRelay;

    public PlayStoreBillingViewModel(@NonNull Application application) {
        super(application);
        repository = BillingRepository.getInstance(application);
        compositeDisposable = new CompositeDisposable();
        subscriptionStateBehaviorRelay = BehaviorRelay.create();
        skuDetailsBehaviorRelay = BehaviorRelay.create();
    }

    public Flowable<SubscriptionState> subscriptionStatusFlowable() {
        return subscriptionStateBehaviorRelay
                .toFlowable(BackpressureStrategy.LATEST);
    }

    public Single<List<SkuDetails>> allSkuDetailsSingle() {
        return skuDetailsBehaviorRelay
                .firstOrError();
    }

    public void startObservingIabUpdates() {
        compositeDisposable.add(
                repository.observeUpdates()
                        .subscribe(
                                purchasesUpdate -> {
                                    if (purchasesUpdate.responseCode() == BillingClient.BillingResponseCode.OK) {
                                        processPurchases(purchasesUpdate.purchases());
                                    } else if (purchasesUpdate.responseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                                        queryCurrentSubscriptionStatus();
                                    } else {
                                        Utils.MyLog.g("BillingRepository::observeUpdates purchase update error response code: " + purchasesUpdate.responseCode());
                                    }
                                },
                                err -> {
                                    subscriptionStateBehaviorRelay.accept(SubscriptionState.billingError(err));
                                }
                        )
        );
    }

    public void stopObservingIabUpdates() {
        compositeDisposable.dispose();
    }


    private Single<List<SkuDetails>> getConsumablesSkuDetails() {
        List<String> ids = new ArrayList<>(BillingRepository.IAB_TIMEPASS_SKUS_TO_DAYS.keySet());
        ids.addAll(new ArrayList<>(BillingRepository.IAB_PSICASH_SKUS_TO_VALUE.keySet()));
        return repository.getSkuDetails(ids, BillingClient.SkuType.INAPP);
    }

    private Single<List<SkuDetails>> getSubscriptionsSkuDetails() {
        List<String> ids = Arrays.asList(
                BillingRepository.IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU,
                BillingRepository.IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU
        );
        return repository.getSkuDetails(ids, BillingClient.SkuType.SUBS);
    }

    public void queryAllSkuDetails() {
        compositeDisposable.add(
        Single.mergeDelayError(getSubscriptionsSkuDetails(), getConsumablesSkuDetails())
                .flatMapIterable(skuDetails -> skuDetails)
                .toList()
                .onErrorReturnItem(Collections.emptyList())
                .subscribe(skuDetailsBehaviorRelay)
        );
    }

    public void queryCurrentSubscriptionStatus() {
        compositeDisposable.add(
                Single.mergeDelayError(repository.getSubscriptions(), repository.getPurchases())
                        .toList()
                        .map(listOfLists -> {
                            List<Purchase> purchaseList = new ArrayList<>();
                            for (List<Purchase> list : listOfLists) {
                                purchaseList.addAll(list);
                            }
                            return purchaseList;
                        })
                        .subscribe(
                                this::processPurchases,
                                err -> subscriptionStateBehaviorRelay.accept(SubscriptionState.billingError(err))
                        )
        );
    }

    private void processPurchases(List<Purchase> purchaseList) {
        if (purchaseList == null || purchaseList.size() == 0) {
            subscriptionStateBehaviorRelay.accept(SubscriptionState.noSubscription());
            return;
        }

        for (Purchase purchase : purchaseList) {
            // Skip purchase with pending or unspecified state.
            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
                continue;
            }

            // Skip purchases that don't pass signature verification.
            if (!Security.verifyPurchase(BillingRepository.IAB_PUBLIC_KEY,
                    purchase.getOriginalJson(), purchase.getSignature())) {
                Utils.MyLog.g("StatusActivityBillingViewModel::processPurchases: failed verification for purchase: " + purchase);
                continue;
            }

            // From Google sample app:
            // If you do not acknowledge a purchase, the Google Play Store will provide a refund to the
            // users within a few days of the transaction. Therefore you have to implement
            // [BillingClient.acknowledgePurchaseAsync] inside your app.
            compositeDisposable.add(repository.acknowledgePurchase(purchase).subscribe());

            if (hasUnlimitedSubscription(purchase)) {
                subscriptionStateBehaviorRelay.accept(SubscriptionState.unlimitedSubscription(purchase));
                return;
            } else if (hasLimitedSubscription(purchase)) {
                subscriptionStateBehaviorRelay.accept(SubscriptionState.limitedSubscription(purchase));
                return;
            } else if (hasTimePass(purchase)) {
                subscriptionStateBehaviorRelay.accept(SubscriptionState.timePass(purchase));
                return;
            }
        }

        subscriptionStateBehaviorRelay.accept(SubscriptionState.noSubscription());
    }

    private boolean hasUnlimitedSubscription(@NonNull Purchase purchase) {
        return Arrays.asList(BillingRepository.IAB_ALL_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS).contains(purchase.getSku());
    }

    private boolean hasLimitedSubscription(@NonNull Purchase purchase) {
        return purchase.getSku().equals(BillingRepository.IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU);
    }

    private boolean hasTimePass(@NonNull Purchase purchase) {
        String purchaseSku = purchase.getSku();
        Long lifetimeInDays = BillingRepository.IAB_TIMEPASS_SKUS_TO_DAYS.get(purchaseSku);
        if (lifetimeInDays == null) {
            // not a time pass SKU
            return false;
        }
        // calculate expiry date based on the lifetime and purchase date
        long lifetimeMillis = lifetimeInDays * 24 * 60 * 60 * 1000;
        long timepassExpiryMillis = purchase.getPurchaseTime() + lifetimeMillis;
        if (System.currentTimeMillis() < timepassExpiryMillis) {
            // This time pass is still valid.
            return true;
        }
        // Otherwise consume the purchase and return false.
        compositeDisposable.add(repository.consumePurchase(purchase).subscribe());

        return false;
    }

    public Completable launchFlow(Activity activity, SkuDetails skuDetails) {
        return repository.launchFlow(activity, null, skuDetails);
    }

    public Completable launchFlow(Activity activity, String oldSku, SkuDetails skuDetails) {
        return repository.launchFlow(activity, oldSku, skuDetails);
    }

    public Single<List<SkuDetails>> getUnlimitedSubscriptionSkuDetails() {
        List<String> ids = Collections.singletonList(
                BillingRepository.IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU
        );
        return repository.getSkuDetails(ids, BillingClient.SkuType.SUBS);
    }

}
