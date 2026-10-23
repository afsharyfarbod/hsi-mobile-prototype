import h5py
import numpy as np

def bin_if_needed(input_path, output_path, max_bytes=150*1024*1024):
    with h5py.File(input_path, 'r') as f:
        cube = f['cube']
        wls = f['wavelengths'][:]
        h, w, b = cube.shape

        # Calculate raw bytes (float32 = 4 bytes)
        current_bytes = h * w * b * 4
        if current_bytes <= max_bytes:
            # No binning needed
            with h5py.File(output_path, 'w') as out:
                out.create_dataset('cube', data=cube[:].astype(np.float32))
                out.create_dataset('wavelengths', data=wls)
            return 1

        # Calculate bin factor
        bin_factor = 1
        while (h // bin_factor) * (w // bin_factor) * b * 4 > max_bytes:
            bin_factor += 1

        new_h = h // bin_factor
        new_w = w // bin_factor

        with h5py.File(output_path, 'w') as out:
            out_cube = out.create_dataset('cube', shape=(new_h, new_w, b), dtype=np.float32)
            out.create_dataset('wavelengths', data=wls)

            # Read row-by-row to keep RAM usage near zero
            for y in range(new_h):
                y_start = y * bin_factor
                y_end = y_start + bin_factor
                chunk = cube[y_start:y_end, :, :]

                # Crop to fit bin factor perfectly
                chunk = chunk[:, :new_w * bin_factor, :]
                
                # Reshape and average (bins spatial pixels together)
                chunk_binned = chunk.reshape(bin_factor, new_w, bin_factor, b).mean(axis=(0, 2))
                out_cube[y, :, :] = chunk_binned

        return bin_factor
