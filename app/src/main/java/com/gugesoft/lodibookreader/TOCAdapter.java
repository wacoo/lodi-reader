package com.gugesoft.lodibookreader;

import android.graphics.Paint;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class TOCAdapter extends RecyclerView.Adapter<TOCAdapter.ViewHolder> {
    private List<BookLoader.TOCItem> tocItems;
    private OnTOCItemClickListener listener;

    public interface OnTOCItemClickListener {
        void onItemClick(BookLoader.TOCItem item);
    }

    public TOCAdapter(List<BookLoader.TOCItem> tocItems, OnTOCItemClickListener listener) {
        this.tocItems = tocItems;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView tv = new TextView(parent.getContext());
        tv.setPadding(32, 24, 32, 24);
        tv.setTextSize(18);
        return new ViewHolder(tv);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BookLoader.TOCItem item = tocItems.get(position);
        holder.textView.setText(item.title);

        // Make it look like a link
        holder.textView.setTextColor(
                ContextCompat.getColor(holder.itemView.getContext(), R.color.linkColor)
        );
        holder.textView.setPaintFlags(
                holder.textView.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG
        );

        holder.textView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item);
            }
        });
    }


    @Override
    public int getItemCount() {
        return tocItems != null ? tocItems.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textView;

        ViewHolder(@NonNull TextView itemView) {
            super(itemView);
            textView = itemView;
        }
    }
}