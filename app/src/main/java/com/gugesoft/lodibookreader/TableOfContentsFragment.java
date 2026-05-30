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
    private List<Sentence> allSentences;
    private OnTOCClickListener listener;

    public interface OnTOCClickListener {
        void onSentenceSelected(int index);
    }

    public TableOfContentsFragment(List<BookLoader.TOCItem> tocItems, List<Sentence> allSentences, OnTOCClickListener listener) {
        this.tocItems = tocItems;
        this.allSentences = allSentences;
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
                int index = findSentenceIndex(item.href);
                if (index != -1) {
                    listener.onSentenceSelected(index);
                }
            }
        }));
        return view;
    }

    private int findSentenceIndex(String href) {
        if (allSentences == null || href == null) return -1;
        
        // Exact match
        for (int i = 0; i < allSentences.size(); i++) {
            Sentence s = allSentences.get(i);
            if (href.equals(s.getInternalId())) {
                return i;
            }
        }
        
        // Match resource only (if href is just the file name but sentence has file#id)
        for (int i = 0; i < allSentences.size(); i++) {
            Sentence s = allSentences.get(i);
            String sId = s.getInternalId();
            if (sId != null && sId.startsWith(href)) {
                return i;
            }
        }
        return -1;
    }
}