package com.termux.app.terminal.io;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.terminal.TerminalSession;

public class TerminalToolbarViewPager {

    public static final int EXTRA_KEYS_PAGE = 0;
    public static final int ADDITIONAL_EXTRA_KEYS_PAGE = 1;
    public static final int TEXT_INPUT_PAGE = 2;

    public static class PageAdapter extends PagerAdapter {

        final TermuxActivity mActivity;
        final ExtraKeysView[] mExtraKeysViews = new ExtraKeysView[2];
        String mSavedTextInput;

        public PageAdapter(TermuxActivity activity, String savedTextInput) {
            this.mActivity = activity;
            this.mSavedTextInput = savedTextInput;
        }

        @Override
        public int getCount() {
            return 3;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup collection, int position) {
            LayoutInflater inflater = LayoutInflater.from(mActivity);
            View layout;
            if (position != TEXT_INPUT_PAGE) {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_extra_keys, collection, false);
                ExtraKeysView extraKeysView = (ExtraKeysView) layout;
                extraKeysView.setExtraKeysViewClient(mActivity.getTermuxTerminalExtraKeys());
                mExtraKeysViews[position] = extraKeysView;
                // Both key pages use the primary page's modifier state.
                if (position == EXTRA_KEYS_PAGE) mActivity.setExtraKeysView(extraKeysView);
                reloadExtraKeys(position);

                // apply extra keys fix if enabled in prefs
                if (position == EXTRA_KEYS_PAGE && mActivity.getProperties().isUsingFullScreen() &&
                    mActivity.getProperties().isUsingFullScreenWorkAround()) {
                    FullScreenWorkAround.apply(mActivity);
                }

            } else {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_text_input, collection, false);
                final EditText editText = layout.findViewById(R.id.terminal_toolbar_text_input);

                if (mSavedTextInput != null) {
                    editText.setText(mSavedTextInput);
                    mSavedTextInput = null;
                }

                editText.setOnEditorActionListener((v, actionId, event) -> {
                    TerminalSession session = mActivity.getCurrentSession();
                    if (session != null) {
                        if (session.isRunning()) {
                            String textToSend = editText.getText().toString();
                            if (textToSend.length() == 0) textToSend = "\r";
                            session.write(textToSend);
                        } else {
                            mActivity.getTermuxTerminalSessionClient().removeFinishedSession(session);
                        }
                        editText.setText("");
                    }
                    return true;
                });
            }
            collection.addView(layout);
            return layout;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup collection, int position, @NonNull Object view) {
            if (position != TEXT_INPUT_PAGE) {
                mExtraKeysViews[position] = null;
                if (position == EXTRA_KEYS_PAGE) mActivity.setExtraKeysView(null);
            }
            collection.removeView((View) view);
        }

        public void reloadExtraKeys() {
            for (int position = 0; position < mExtraKeysViews.length; position++) {
                reloadExtraKeys(position);
            }
        }

        private void reloadExtraKeys(int position) {
            ExtraKeysView extraKeysView = mExtraKeysViews[position];
            if (extraKeysView == null) return;
            TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
            extraKeysView.setButtonTextAllCaps(mActivity.getProperties().shouldExtraKeysTextBeAllCaps());
            extraKeysView.reload(position == EXTRA_KEYS_PAGE ? extraKeys.getExtraKeysInfo() : extraKeys.getAdditionalExtraKeysInfo(),
                mActivity.getTerminalToolbarDefaultHeight());
        }

    }



    public static class OnPageChangeListener extends ViewPager.SimpleOnPageChangeListener {

        final TermuxActivity mActivity;
        final ViewPager mTerminalToolbarViewPager;

        public OnPageChangeListener(TermuxActivity activity, ViewPager viewPager) {
            this.mActivity = activity;
            this.mTerminalToolbarViewPager = viewPager;
        }

        @Override
        public void onPageSelected(int position) {
            if (position != TEXT_INPUT_PAGE) {
                mActivity.getTerminalView().requestFocus();
            } else {
                final EditText editText = mTerminalToolbarViewPager.findViewById(R.id.terminal_toolbar_text_input);
                if (editText != null) editText.requestFocus();
            }
        }

    }

}
