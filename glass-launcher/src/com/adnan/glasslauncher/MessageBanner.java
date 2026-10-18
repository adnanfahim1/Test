package com.adnan.glasslauncher;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A message from the phone, shown at the top of the screen for a few seconds: who sent it,
 * the text (unless turned off), and big buttons to read it aloud, send a quick reply or mark
 * it read. Nothing to type while driving: replies are fixed phrases.
 */
final class MessageBanner extends LinearLayout {
    static final String[] QUICK_REPLIES = {"Driving, reply soon", "On my way", "OK 👍", "Can't talk"};
    private static final long SHOW_MS = 15000;

    private final MainActivity a;
    private final TextView head, body;
    private final LinearLayout buttons;
    private PhoneBridge.Message msg;

    MessageBanner(MainActivity a) {
        super(a);
        this.a = a;
        setOrientation(VERTICAL);
        setPadding(Ui.u(22), Ui.u(16), Ui.u(22), Ui.u(16));
        setBackground(Ui.fill(0xF2202128, Ui.white(0.2f), 24));
        setClickable(true); // taps don't fall through to the screen below
        head = Ui.text(a, "", 15, Ui.TEXT_2, Ui.body(600));
        addView(head);
        body = Ui.multiline(a, "", 19, Ui.TEXT, Ui.body(500));
        body.setMaxLines(2);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(body, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
        buttons = Ui.row(a);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        addView(buttons, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 12, 0, 0));
        setVisibility(GONE);
    }

    PhoneBridge.Message message() { return getVisibility() == VISIBLE ? msg : null; }

    void show(PhoneBridge.Message m) {
        int mode = a.prefs().messageMode();
        if (mode == 0) return;
        msg = m;
        String who = m.from != null ? m.from : "Someone";
        Ui.setText(head, (m.app != null ? m.app + "  ·  " : "") + "Message from " + who);
        body.setText(mode == 2 ? m.text : "Tap Read aloud to hear it");
        mainButtons();
        setVisibility(VISIBLE);
        Ui.rise(this, 0);
        keepUp();
        if (a.prefs().readMessagesAloud()) readAloud();
    }

    private void mainButtons() {
        buttons.removeAllViews();
        if (a.speaker().available()) add(Parts.accentButton(a, "Read aloud", 16, 20, 52, 26, new OnClickListener() {
            @Override
            public void onClick(View v) { readAloud(); keepUp(); }
        }));
        if (msg.canReply) add(Parts.outlineButton(a, "Reply", 16, 20, 52, 26, new OnClickListener() {
            @Override
            public void onClick(View v) { replies(); keepUp(); }
        }));
        if (msg.canMarkRead) add(Parts.outlineButton(a, "Mark read", 16, 20, 52, 26, new OnClickListener() {
            @Override
            public void onClick(View v) {
                a.bridge().markMessageRead(msg.key);
                hide();
            }
        }));
        buttons.addView(Ui.space(a), Ui.lpw(0, 1, 1));
        add(Parts.outlineButton(a, "Close", 16, 20, 52, 26, new OnClickListener() {
            @Override
            public void onClick(View v) { hide(); }
        }));
    }

    private void replies() {
        buttons.removeAllViews();
        for (final String r : QUICK_REPLIES) {
            add(Parts.outlineButton(a, r, 15, 14, 52, 26, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.bridge().replyMessage(msg.key, r);
                    a.toast("Reply sent: " + r);
                    hide();
                }
            }));
        }
        buttons.addView(Ui.space(a), Ui.lpw(0, 1, 1));
        add(Parts.outlineButton(a, "Back", 15, 16, 52, 26, new OnClickListener() {
            @Override
            public void onClick(View v) { mainButtons(); keepUp(); }
        }));
    }

    private void add(View b) {
        buttons.addView(b, Ui.margins(Ui.lp(Ui.WRAP, Ui.u(52)), buttons.getChildCount() == 0 ? 0 : 10, 0, 0, 0));
    }

    private void readAloud() {
        if (msg == null) return;
        String who = msg.from != null ? msg.from : "someone";
        a.speaker().say("Message from " + who + ". " + msg.text);
    }

    private final Runnable autoHide = new Runnable() {
        @Override
        public void run() { hide(); }
    };

    private void keepUp() {
        removeCallbacks(autoHide);
        postDelayed(autoHide, SHOW_MS);
    }

    void hide() {
        removeCallbacks(autoHide);
        setVisibility(GONE);
    }
}
