package com.psiphon3.subscription.ui.premium;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class PremiumOptionsViewModel extends ViewModel {

    private MutableLiveData<String> mText;

    public PremiumOptionsViewModel() {
        mText = new MutableLiveData<>();
        mText.setValue("This is premium options fragment");
    }

    public LiveData<String> getText() {
        return mText;
    }
}