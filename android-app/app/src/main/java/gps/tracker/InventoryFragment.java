package gps.tracker;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import gps.tracker.databinding.FragmentInventoryBinding;
import soturi.model.Item;

public class InventoryFragment extends Fragment {

    FragmentInventoryBinding binding;
    MainActivity mainActivity;
    ItemManager itemManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = FragmentInventoryBinding.inflate(getLayoutInflater());
        mainActivity = (MainActivity) getActivity();
        itemManager = mainActivity.getItemManager();


        for (Item.ItemType type : Item.ItemType.values()) {
            MaterialButton button = new MaterialButton(mainActivity);
            button.setText(type.name());


            mainActivity.runOnUiThread(
                    () -> {
                        binding.itemTypeLayout.addView(button);
                    }
            );
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return binding.getRoot();
    }


}