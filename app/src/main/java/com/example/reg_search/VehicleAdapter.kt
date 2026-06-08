package com.example.reg_search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class VehicleAdapter(
    private var vehicles: List<Vehicle>,
    private val onItemClick: (Vehicle) -> Unit
) : RecyclerView.Adapter<VehicleAdapter.VehicleViewHolder>() {

    class VehicleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvReg: TextView = view.findViewById(R.id.tvReg)
        val tvMake: TextView = view.findViewById(R.id.tvMake)
        val tvColor: TextView = view.findViewById(R.id.tvColor)
        val tvDetails: TextView = view.findViewById(R.id.tvDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VehicleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_vehicle, parent, false)
        return VehicleViewHolder(view)
    }

    override fun onBindViewHolder(holder: VehicleViewHolder, position: Int) {
        val vehicle = vehicles[position]
        holder.tvReg.text = vehicle.registrationNumber
        holder.tvMake.text = vehicle.make
        holder.tvColor.text = vehicle.colour

        val details = StringBuilder()
        val json = vehicle.rawData
        val importantKeys = listOf("yearOfManufacture", "fuelType", "engineCapacity")
        for (key in importantKeys) {
            if (json.has(key)) {
                details.append("${key}: ${json.get(key)}\n")
            }
        }
        holder.tvDetails.text = details.toString()

        holder.itemView.setOnClickListener {
            onItemClick(vehicle)
        }
    }

    override fun getItemCount() = vehicles.size

    fun updateData(newVehicles: List<Vehicle>) {
        vehicles = newVehicles
        notifyDataSetChanged()
    }
}
