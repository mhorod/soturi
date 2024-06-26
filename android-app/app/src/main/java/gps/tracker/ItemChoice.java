package gps.tracker;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.button.MaterialButton;

import java.io.InputStream;
import java.util.List;

import gps.tracker.databinding.FragmentItemChoiceBinding;
import gps.tracker.views.ObjectWithDescription;
import soturi.model.Item;

public class ItemChoice extends Fragment {

    FragmentItemChoiceBinding binding;
    MainActivity mainActivity;
    ItemManager itemManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = FragmentItemChoiceBinding.inflate(getLayoutInflater());
        mainActivity = (MainActivity) getActivity();
        itemManager = mainActivity.getItemManager();

        Item.ItemType type = itemManager.getItemTypeOfInterest();

        if (type == null) {
            // We have a problem. This should never happen.
            mainActivity.runOnUiThread(
                    // Go back
                    NavHostFragment.findNavController(ItemChoice.this)::popBackStack
            );
        }

        List<Item> equippedItems = itemManager.getEquippedItemsOfType(type);

        if (equippedItems.isEmpty()) {
            TextView textView = new TextView(mainActivity);
            textView.setText("No items of this type are currently equipped.");
            textView.setGravity(Gravity.CENTER_HORIZONTAL);

            binding.itemChoiceLayout.addView(textView);
        } else {
            TextView textView = new TextView(mainActivity);
            textView.setText("Currently equipped item:");
            textView.setGravity(Gravity.CENTER_HORIZONTAL);

            Space verticalSpace = new Space(mainActivity);
            verticalSpace.setMinimumHeight(50);

            Item currentlyEquippedItem = equippedItems.get(0);
            FullItemInfoSegment currentlyOwnedItemSegment = new FullItemInfoSegment(mainActivity, currentlyEquippedItem);
            Space verticalSpace2 = new Space(mainActivity);
            verticalSpace2.setMinimumHeight(100);

            mainActivity.runOnUiThread(
                    () -> {
                        binding.itemChoiceLayout.addView(textView);
                        binding.itemChoiceLayout.addView(verticalSpace);
                        binding.itemChoiceLayout.addView(currentlyOwnedItemSegment);
                        binding.itemChoiceLayout.addView(verticalSpace2);
                    }
            );
        }

        TextView unequippedItemsText = new TextView(mainActivity);
        unequippedItemsText.setText("Inventory:");
        unequippedItemsText.setGravity(Gravity.CENTER_HORIZONTAL);

        Space verticalSpace3 = new Space(mainActivity);
        verticalSpace3.setMinimumHeight(50);

        mainActivity.runOnUiThread(
                () -> {
                    binding.itemChoiceLayout.addView(unequippedItemsText);
                    binding.itemChoiceLayout.addView(verticalSpace3);
                }
        );

        List<Item> items = itemManager.getItemsOfType(type);

        for (Item item : items) {
            FullItemInfoWithEquipButton choiceSegment = new FullItemInfoWithEquipButton(mainActivity, item);
            Space verticalSpace = new Space(mainActivity);
            verticalSpace.setMinimumHeight(20);

            mainActivity.runOnUiThread(
                    () -> {
                        binding.itemChoiceLayout.addView(choiceSegment);
                        binding.itemChoiceLayout.addView(verticalSpace);
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

    public static class FullItemInfoSegment extends LinearLayout {
        public FullItemInfoSegment(Context context, @NonNull Item item) {
            super(context);

            this.setOrientation(LinearLayout.HORIZONTAL);

            Bitmap itemBitmap = getBitmapFromItem(item);
            itemBitmap = Bitmap.createScaledBitmap(itemBitmap, 89, 89, false);


            ObjectWithDescription objectWithDescription = new ObjectWithDescription(
                    context,
                    item.name(),
                    itemBitmap,
                    20
            );

            ObjectWithDescription hpDescription = new ObjectWithDescription(
                    context,
                    String.valueOf(item.statistics().maxHp()),
                    R.drawable.heart,
                    20
            );

            ObjectWithDescription attackDescription = new ObjectWithDescription(
                    context,
                    String.valueOf(item.statistics().attack()),
                    R.drawable.sword2,
                    20
            );

            ObjectWithDescription defenseDescription = new ObjectWithDescription(
                    context,
                    String.valueOf(item.statistics().defense()),
                    R.drawable.shield,
                    20
            );


            Space horizontalSpace = new Space(context);
            horizontalSpace.setMinimumWidth(50);

            Space horizontalSpace2 = new Space(context);
            horizontalSpace2.setMinimumWidth(50);

            Space horizontalSpace3 = new Space(context);
            horizontalSpace3.setMinimumWidth(50);

            this.addView(objectWithDescription);
            this.addView(horizontalSpace);
            this.addView(hpDescription);
            this.addView(horizontalSpace2);
            this.addView(attackDescription);
            this.addView(horizontalSpace3);
            this.addView(defenseDescription);

            this.setGravity(Gravity.CENTER_HORIZONTAL);
        }

        private Bitmap getBitmapFromItem(@NonNull Item item) {
            InputStream stream = getClass().getClassLoader().getResourceAsStream(item.gfxName());
            Drawable draw = Drawable.createFromStream(stream, null);
            BitmapDrawable bitmapDrawable = (BitmapDrawable) draw;
            return bitmapDrawable.getBitmap();
        }
    }

    private class FullItemInfoWithEquipButton extends FullItemInfoSegment {

        public FullItemInfoWithEquipButton(Context context, @NonNull Item item) {
            super(context, item);

            Space horizontalSpace = new Space(context);
            horizontalSpace.setMinimumWidth(50);

            MaterialButton equipButton = new MaterialButton(context);
            equipButton.setText("Equip");

            equipButton.setOnClickListener(
                    v -> {
                        mainActivity.getWebSocketClient().send().equipItem(item.itemId());
                        mainActivity.runOnUiThread(
                                () -> {
                                    NavHostFragment.findNavController(ItemChoice.this).popBackStack();
                                    AlertDialog.Builder builder = new AlertDialog.Builder(mainActivity);
                                    builder.setMessage("You have equipped " + item.name() + "!");
                                    builder.setPositiveButton("Splendid!", (ignore, ignore2) -> {
                                    });
                                    builder.show();
                                }
                        );
                    }
            );

            this.addView(horizontalSpace);
            this.addView(equipButton);
        }
    }
}