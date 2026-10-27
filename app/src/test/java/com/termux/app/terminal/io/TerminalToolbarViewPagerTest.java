package com.termux.app.terminal.io;

import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.view.TerminalView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TerminalToolbarViewPagerTest {

    private TermuxActivity activity;
    private TerminalView terminal;
    private ViewPager pager;
    private TerminalToolbarViewPager.PageAdapter adapter;

    @Before
    public void setUp() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        ReflectionHelpers.setField(activity, "mProperties", TermuxAppSharedProperties.init(activity));
        ReflectionHelpers.setField(activity, "mPreferences", TermuxAppSharedPreferences.build(activity));

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        terminal = new TerminalView(activity, null);
        terminal.setFocusableInTouchMode(true);
        ReflectionHelpers.setField(activity, "mTerminalView", terminal);
        root.addView(terminal, new LinearLayout.LayoutParams(600, 400));
        pager = new ViewPager(activity);
        pager.setId(R.id.terminal_toolbar_view_pager);
        root.addView(pager, new LinearLayout.LayoutParams(600, 40));
        activity.setContentView(root);

        ReflectionHelpers.callInstanceMethod(activity, "setTerminalToolbarView",
            ReflectionHelpers.ClassParameter.from(Bundle.class, null));
        adapter = (TerminalToolbarViewPager.PageAdapter) pager.getAdapter();
        controller.visible();
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 600, 600);
    }

    @Test
    public void secondPageSendsDollarShiftLeftAndOriginalNavigationKeys() {
        pager.setCurrentItem(TerminalToolbarViewPager.ADDITIONAL_EXTRA_KEYS_PAGE, false);
        ExtraKeysView keys = adapter.mExtraKeysViews[TerminalToolbarViewPager.ADDITIONAL_EXTRA_KEYS_PAGE];
        List<String> sentKeys = new ArrayList<>();
        keys.setExtraKeysViewClient(new TerminalExtraKeys(terminal) {
            @Override
            protected void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown,
                                                         boolean altDown, boolean shiftDown, boolean fnDown) {
                assertFalse(ctrlDown);
                assertFalse(altDown);
                assertFalse(fnDown);
                sentKeys.add((shiftDown ? "SHIFT " : "") + key);
            }
        });

        for (int i = 0; i < keys.getChildCount(); i++) keys.getChildAt(i).performClick();

        assertEquals(Arrays.asList("HOME", "END", "$", "PGUP", "PGDN", "SHIFT LEFT"), sentKeys);
    }

    @Test
    public void switchingPagesKeepsDraftAndPrimaryModifierState() {
        assertEquals(3, adapter.getCount());
        ExtraKeysView primary = activity.getExtraKeysView();
        primary.getChildAt(8).performClick();
        assertEquals(Boolean.TRUE, primary.readSpecialButton(SpecialButton.CTRL, false));

        pager.setCurrentItem(TerminalToolbarViewPager.TEXT_INPUT_PAGE, false);
        EditText input = pager.findViewById(R.id.terminal_toolbar_text_input);
        input.setText("unfinished command");
        assertTrue(activity.isTerminalToolbarTextInputViewSelected());
        assertTrue(input.hasFocus());

        pager.setCurrentItem(TerminalToolbarViewPager.EXTRA_KEYS_PAGE, false);
        pager.setCurrentItem(TerminalToolbarViewPager.ADDITIONAL_EXTRA_KEYS_PAGE, false);
        assertTrue(activity.isTerminalViewSelected());
        assertTrue(terminal.hasFocus());
        assertSame(primary, activity.getExtraKeysView());
        assertEquals(Boolean.TRUE, primary.readSpecialButton(SpecialButton.CTRL, true));

        pager.setCurrentItem(TerminalToolbarViewPager.TEXT_INPUT_PAGE, false);
        assertSame(input, pager.findViewById(R.id.terminal_toolbar_text_input));
        assertEquals("unfinished command", input.getText().toString());
    }

}
