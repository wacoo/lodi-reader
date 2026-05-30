package com.gugesoft.lodibookreader;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class SentenceAdapter extends RecyclerView.Adapter<SentenceAdapter.ViewHolder> {

    private List<Sentence> sentences;
    private int highlightedIndex = -1;
    private OnSentenceClickListener listener;
    private SettingsManager settings;

    public interface OnSentenceClickListener {
        void onSentenceClick(int position);
        void onNavigateTo(int position); 
        void onSingleTap();
    }

    public SentenceAdapter(List<Sentence> sentences, OnSentenceClickListener listener, SettingsManager settings) {
        this.sentences = sentences;
        this.listener = listener;
        this.settings = settings;
    }

    public void setHighlighted(int index) {
        highlightedIndex = index;
        notifyDataSetChanged();
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        TextView tv = new TextView(parent.getContext());
        tv.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        tv.setPadding(32, 24, 32, 24);
        tv.setLineSpacing(0, 1.2f);
        return new ViewHolder(tv);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        Sentence sentence = sentences.get(position);
        String text = sentence.getText();
        
        // Apply appearance settings
        holder.textView.setTextColor(settings.getFontColor());
        holder.textView.setTextSize(settings.getFontSize());
        try {
            holder.textView.setTypeface(Typeface.create(settings.getFontFamily(), Typeface.NORMAL));
        } catch (Exception e) {
            holder.textView.setTypeface(Typeface.SANS_SERIF);
        }

        // Highlight logic
        if (position == highlightedIndex) {
            // Semi-transparent blue highlight that works on any background
            holder.textView.setBackgroundColor(Color.argb(80, 187, 222, 251));
        } else {
            holder.textView.setBackgroundColor(Color.TRANSPARENT);
        }

        // Style links if present
        if (sentence.getLink() != null) {
            SpannableString ss = new SpannableString(text);
            ss.setSpan(new UnderlineSpan(), 0, text.length(), 0);
            ss.setSpan(new ForegroundColorSpan(Color.BLUE), 0, text.length(), 0);
            holder.textView.setText(ss);
        } else {
            holder.textView.setText(text);
        }

        // Click handling
        holder.textView.setOnClickListener(new DoubleClickListener() {
            @Override
            public void onDoubleClick(View v) {
                if (listener != null) listener.onSentenceClick(position);
            }

            @Override
            public void onSingleClick(View v) {
                if (sentence.getLink() != null) {
                    String link = sentence.getLink();
                    if (link.startsWith("http")) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                            v.getContext().startActivity(intent);
                            return;
                        } catch (Exception ignored) {}
                    } else {
                        int target = findSentenceIndexByInternalId(link);
                        if (target != -1) {
                            if (listener != null) {
                                listener.onNavigateTo(target);
                            }
                            highlightedIndex = target;
                            notifyDataSetChanged();
                            return;
                        }
                    }
                }

                if (listener != null) listener.onSingleTap();
            }
        });
    }

    private int findSentenceIndexByInternalId(String linkHref) {
        if (linkHref == null) return -1;

        // If link contains "#", extract only the anchor part
        String anchorOnly = linkHref.contains("#")
                ? linkHref.substring(linkHref.indexOf("#") + 1)
                : linkHref;

        for (int i = 0; i < sentences.size(); i++) {
            String sId = sentences.get(i).getInternalId();
            if (sId != null) {
                // Match exact anchor or id ending with anchor
                if (sId.equals(anchorOnly) || sId.endsWith(anchorOnly)) {
                    return i;
                }
            }
        }

        return -1;
    }



    @Override
    public int getItemCount() {
        return sentences.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textView;

        ViewHolder(View itemView) {
            super(itemView);
            textView = (TextView) itemView;
        }
    }
}