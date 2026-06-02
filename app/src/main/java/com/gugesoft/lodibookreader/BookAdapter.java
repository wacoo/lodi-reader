package com.gugesoft.lodibookreader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BookAdapter extends RecyclerView.Adapter<BookAdapter.ViewHolder> {

    private static final int[] COLORS = {
            0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7,
            0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF009688,
            0xFF4CAF50, 0xFF8BC34A, 0xFFFF9800, 0xFFFF5722
    };

    private List<BookItem> books;
    private OnBookClickListener listener;
    private Set<Integer> selectedPositions = new HashSet<>();
    private boolean selectionMode = false;
    private OnSelectionModeChangeListener selectionModeListener;

    public interface OnBookClickListener {
        void onClick(BookItem book);
    }

    public interface OnSelectionModeChangeListener {
        void onSelectionModeChanged(boolean active);
    }

    public BookAdapter(List<BookItem> books, OnBookClickListener listener) {
        this.books = books;
        this.listener = listener;
    }

    public void setOnSelectionModeChange(OnSelectionModeChangeListener l) {
        this.selectionModeListener = l;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_book_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        BookItem book = books.get(position);

        holder.tvTitle.setText(book.title);
        holder.tvAuthor.setText(book.author.isEmpty() ? "Unknown Author" : book.author);
        
        if (book.totalPages > 0) {
            holder.tvProgress.setText(String.format(Locale.getDefault(), "Page %d of %d", book.currentPage + 1, book.totalPages));
        } else {
            holder.tvProgress.setText("Not started");
        }

        // Try to load cover if available, otherwise show initials
        boolean coverLoaded = false;
        if (book.coverUri != null && !book.coverUri.isEmpty()) {
            try {
                Uri uri = Uri.parse(book.coverUri);
                File imgFile = new File(uri.getPath());
                if (imgFile.exists()) {
                    Bitmap bitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                    if (bitmap != null) {
                        holder.ivCover.setImageBitmap(bitmap);
                        holder.ivCover.setVisibility(View.VISIBLE);
                        holder.tvInitials.setVisibility(View.GONE);
                        coverLoaded = true;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (!coverLoaded) {
            showInitials(holder, book);
        }

        holder.radioButton.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.radioButton.setChecked(selectedPositions.contains(position));

        holder.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelection(position);
            } else {
                if (listener != null) listener.onClick(book);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!selectionMode) {
                selectionMode = true;
                toggleSelection(position);
                notifyDataSetChanged();
                if (selectionModeListener != null) selectionModeListener.onSelectionModeChanged(true);
            }
            return true;
        });
    }

    private void showInitials(ViewHolder holder, BookItem book) {
        holder.ivCover.setVisibility(View.GONE);
        holder.tvInitials.setVisibility(View.VISIBLE);
        
        String initials = getInitials(book.title);
        holder.tvInitials.setText(initials);
        
        int colorIndex = Math.abs(book.title.hashCode()) % COLORS.length;
        holder.tvInitials.setBackgroundColor(COLORS[colorIndex]);
    }

    private String getInitials(String title) {
        if (title == null || title.isEmpty()) return "?";
        String[] words = title.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0)));
            if (sb.length() >= 2) break;
        }
        return sb.toString();
    }

    private void toggleSelection(int pos) {
        if (selectedPositions.contains(pos)) {
            selectedPositions.remove(pos);
            if (selectedPositions.isEmpty()) {
                exitSelectionMode();
            } else {
                notifyItemChanged(pos);
            }
        } else {
            selectedPositions.add(pos);
            notifyItemChanged(pos);
        }
    }

    public void exitSelectionMode() {
        selectionMode = false;
        selectedPositions.clear();
        notifyDataSetChanged();
        if (selectionModeListener != null) selectionModeListener.onSelectionModeChanged(false);
    }

    public List<BookItem> getSelectedBooks() {
        List<BookItem> selected = new ArrayList<>();
        for (Integer pos : selectedPositions) {
            selected.add(books.get(pos));
        }
        return selected;
    }

    @Override
    public int getItemCount() {
        return books.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivCover;
        TextView tvInitials;
        TextView tvTitle;
        TextView tvAuthor;
        TextView tvProgress;
        RadioButton radioButton;

        ViewHolder(View itemView) {
            super(itemView);
            ivCover = itemView.findViewById(R.id.ivCover);
            tvInitials = itemView.findViewById(R.id.tvInitials);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvAuthor = itemView.findViewById(R.id.tvAuthor);
            tvProgress = itemView.findViewById(R.id.tvProgress);
            radioButton = itemView.findViewById(R.id.radioButtonSelect);
        }
    }
}
