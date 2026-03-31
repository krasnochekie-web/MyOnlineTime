public class HeaderAdapter extends RecyclerView.Adapter<HeaderAdapter.HeaderViewHolder> {
    private View view;
    public HeaderAdapter(View view) { this.view = view; }
    
    @Override
    public int getItemCount() { return 1; }
    
    @NonNull @Override
    public HeaderViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
        return new HeaderViewHolder(view);
    }

    @Override public void onBindViewHolder(@NonNull HeaderViewHolder h, int p) {}

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        public HeaderViewHolder(View itemView) { super(itemView); }
    }
}
