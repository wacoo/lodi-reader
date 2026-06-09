package com.gugesoft.lodibookreader;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class TableOfContentsFragment extends Fragment {
    private List<BookLoader.TOCItem> tocItems;
    private OnTOCClickListener listener;

    public interface OnTOCClickListener {
        void onChapterSelected(int chapterIndex);
    }

    public TableOfContentsFragment(List<BookLoader.TOCItem> tocItems, OnTOCClickListener listener) {
        this.tocItems = tocItems;
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_table_of_contents, container, false);
        RecyclerView recyclerView = view.findViewById(R.id.recyclerViewTOC);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        
        recyclerView.setAdapter(new TOCAdapter(tocItems, item -> {
            if (listener != null) {
                listener.onChapterSelected(item.chapterIndex);
            }
        }));
        return view;
    }
}
